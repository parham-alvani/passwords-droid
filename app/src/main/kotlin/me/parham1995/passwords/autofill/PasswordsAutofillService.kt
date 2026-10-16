package me.parham1995.passwords.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.graphics.drawable.Icon
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.util.Log
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.github.androidpasswordstore.autofillparser.AutofillAction
import com.github.androidpasswordstore.autofillparser.AutofillScenario
import com.github.androidpasswordstore.autofillparser.FillableForm
import com.github.androidpasswordstore.autofillparser.FormOrigin
import com.github.androidpasswordstore.autofillparser.fillWith
import com.github.androidpasswordstore.autofillparser.passwordValue
import com.github.androidpasswordstore.autofillparser.recoverNodes
import com.github.androidpasswordstore.autofillparser.usernameValue
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.parham1995.passwords.MainActivity
import me.parham1995.passwords.R
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.store.CredentialMatcher
import javax.inject.Inject

/**
 * Classic Android autofill: what Chrome's "Autofill using another service"
 * and apps without Credential Manager talk to.
 *
 * Password Store's parser reads the form and decides whether a browser can be
 * believed about the page; the store's own matcher picks the entries by name.
 * Each suggestion is locked: choosing it opens [AutofillActivity], which
 * unlocks and decrypts that one entry and hands the values back to fill.
 */
@AndroidEntryPoint
class PasswordsAutofillService : AutofillService() {
    @Inject
    lateinit var repository: PasswordRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null || structure.activityComponent.packageName in DENYLIST) {
            callback.onSuccess(null)
            return
        }
        val form =
            FillableForm.parseAssistStructure(
                this,
                structure,
                isManualRequest = request.flags and FillRequest.FLAG_MANUAL_REQUEST != 0,
            )
        if (form == null) {
            callback.onSuccess(null)
            return
        }
        val job =
            scope.launch {
                if (repository.items.value.isEmpty()) repository.refresh()
                val matches = CredentialMatcher.find(repository.items.value, form.formOrigin.toTarget())
                Log.i(TAG, "fill request from ${form.formOrigin}: ${matches.size} entries")
                callback.onSuccess(response(form, matches, request))
            }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private fun response(
        form: FillableForm,
        matches: List<CredentialMatcher.Match>,
        request: FillRequest,
    ): FillResponse? {
        val clientState = form.toClientState()
        val inline = request.inlineSuggestionsRequest
        val specs = inline?.inlinePresentationSpecs.orEmpty()
        val maxInline = inline?.maxSuggestionCount ?: 0
        var shown = 0

        fun nextSpec(): InlinePresentationSpec? =
            if (shown <
                maxInline
            ) {
                specs.getOrNull(shown) ?: specs.lastOrNull()
            } else {
                null
            }

        val datasets = mutableListOf<Dataset>()
        if (form.scenario.hasFieldsToFillOn(AutofillAction.Match)) {
            matches.forEach { match ->
                val sender = AutofillActivity.fill(this, match.item.name, clientState)
                datasets +=
                    dataset(form.scenario, AutofillAction.Match, sender, match.username, match.item.folder, nextSpec())
                shown++
            }
        }
        if (form.scenario.hasFieldsToFillOn(AutofillAction.Search)) {
            val sender = AutofillActivity.search(this, clientState)
            datasets +=
                dataset(
                    form.scenario,
                    AutofillAction.Search,
                    sender,
                    getString(R.string.credman_search),
                    null,
                    nextSpec(),
                )
        }
        if (datasets.isEmpty()) return null
        return FillResponse
            .Builder()
            .apply {
                datasets.forEach(::addDataset)
                saveInfo(form)?.let(::setSaveInfo)
                setClientState(clientState)
                setIgnoredIds(*form.ignoredIds.toTypedArray())
            }.build()
    }

    private fun dataset(
        scenario: AutofillScenario<android.view.autofill.AutofillId>,
        action: AutofillAction,
        sender: IntentSender,
        title: String,
        subtitle: String?,
        spec: InlinePresentationSpec?,
    ): Dataset {
        val presentations = Presentations.Builder()
        val inline = spec?.let { inlinePresentation(it, title, subtitle) }
        if (inline != null) presentations.setInlinePresentation(inline)
        presentations.setMenuPresentation(menuPresentation(title, subtitle))
        return Dataset
            .Builder(presentations.build())
            .apply {
                fillWith(scenario, action, credentials = null)
                setAuthentication(sender)
            }.build()
    }

    private fun menuPresentation(
        title: String,
        subtitle: String?,
    ) = RemoteViews(packageName, R.layout.autofill_dataset).apply {
        setTextViewText(R.id.title, title)
        setTextViewText(R.id.subtitle, subtitle.orEmpty())
        setImageViewResource(R.id.icon, R.mipmap.ic_launcher)
    }

    // `.slice` is how the androidx inline UI is handed to the platform, as its
    // own documentation does it; lint flags the getter as library-internal.
    @SuppressLint("RestrictedApi")
    private fun inlinePresentation(
        spec: InlinePresentationSpec,
        title: String,
        subtitle: String?,
    ): InlinePresentation? {
        if (UiVersions.INLINE_UI_VERSION_1 !in UiVersions.getVersions(spec.style)) return null
        // Long-pressing a chip opens the app; the system requires something to open.
        val attribution =
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val slice =
            InlineSuggestionUi
                .newContentBuilder(attribution)
                .setTitle(title)
                .apply { subtitle?.takeIf { it.isNotEmpty() }?.let { setSubtitle(it) } }
                .setStartIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setContentDescription(listOfNotNull(title, subtitle).joinToString(" - "))
                .build()
                .slice
        return InlinePresentation(slice, spec, false)
    }

    /** Offer to save what was typed, when the form has a password to save. */
    private fun saveInfo(form: FillableForm): SaveInfo? {
        val flags = form.saveFlags ?: return null
        if (!form.scenario.hasPasswordFieldsToSave) return null
        val ids = form.scenario.fieldsToSave.toTypedArray()
        if (ids.isEmpty()) return null
        var types = SaveInfo.SAVE_DATA_TYPE_PASSWORD
        if (form.scenario.hasUsername) types = types or SaveInfo.SAVE_DATA_TYPE_USERNAME
        return SaveInfo.Builder(types, ids).setFlags(flags).build()
    }

    override fun onSaveRequest(
        request: SaveRequest,
        callback: SaveCallback,
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        val clientState = request.clientState
        val scenario =
            clientState?.let { AutofillScenario.fromClientState(it) }?.let { s ->
                structure?.let { s.recoverNodes(it) }
            }
        val origin = clientState?.let { FormOrigin.fromBundle(it) }
        val password = scenario?.passwordValue
        if (scenario == null || origin == null || password == null) {
            callback.onFailure(getString(R.string.autofill_save_unsupported))
            return
        }
        val username = scenario.usernameValue?.concatToString().orEmpty()
        callback.onSuccess(AutofillActivity.save(this, origin, username, password.concatToString()))
        password.fill('\u0000')
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PasswordsAutofill"

        /** Never fill these: the system, settings, and this app's own fields. */
        val DENYLIST =
            setOf(
                "android",
                "com.android.settings",
                "com.android.settings.intelligence",
                "com.android.systemui",
                "me.parham1995.passwords",
                "me.parham1995.passwords.debug",
            )
    }
}
