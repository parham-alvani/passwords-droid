package me.parham1995.passwords.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.data.SyncScheduler
import java.util.concurrent.TimeUnit

/**
 * Sends commits a save could not push: pulls (rebasing on whatever the
 * desktop's gopass pushed meanwhile, which it does after every change) and
 * pushes. Runs when there is a network, and backs off exponentially while
 * the push keeps failing. A conflict on the same entry is left for a person.
 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repository: PasswordRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            when (repository.pushPending()) {
                PasswordRepository.PushOutcome.DONE -> Result.success()
                PasswordRepository.PushOutcome.RETRY -> Result.retry()
                PasswordRepository.PushOutcome.CONFLICT -> Result.failure()
            }

        companion object {
            private const val NAME = "push-pending"
            private const val BACKOFF_SECONDS = 30L

            /**
             * Enqueues one sync, unless one is already waiting: KEEP, so a
             * burst of offline saves is still one job.
             */
            fun scheduler(context: Context): SyncScheduler =
                SyncScheduler {
                    val request =
                        OneTimeWorkRequestBuilder<SyncWorker>()
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                            .build()
                    WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
                }
        }
    }
