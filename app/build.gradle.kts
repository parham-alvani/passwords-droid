import java.util.Properties

plugins {
    id("passwords.android.application")
    id("passwords.android.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing material, from a gitignored `keystore.properties` at the repo
 * root or, in CI, from the environment. Without it the release build falls
 * back to the debug key so the repository builds for anyone -- which is also
 * why that fallback must never be what ships: an app's identity is its key.
 */
val keystoreProperties =
    Properties().apply {
        providers
            .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
            .asText
            .orNull
            ?.let { load(it.reader()) }
    }

fun signingValue(
    property: String,
    environment: String,
): String? = keystoreProperties.getProperty(property) ?: providers.environmentVariable(environment).orNull

val releaseKeystore: String? = signingValue("storeFile", "PASSWORDS_KEYSTORE")

fun gitValue(vararg args: String): String =
    runCatching {
        providers
            .exec {
                commandLine(listOf("git") + args)
                // A repository with no commit yet has no HEAD; that is "unknown", not a broken build.
                isIgnoreExitValue = true
            }.standardOutput
            .asText
            .get()
            .trim()
    }.getOrDefault("")

android {
    namespace = "me.parham1995.passwords"

    defaultConfig {
        applicationId = "me.parham1995.passwords"
        // Literals on purpose: F-Droid reads them with a regex. `just bump`
        // keeps them in step; CI refuses a tag that disagrees.
        versionCode = 100
        versionName = "0.1.0"

        buildConfigField("String", "GIT_SHA", "\"${gitValue("rev-parse", "--short", "HEAD").ifEmpty { "unknown" }}\"")
        buildConfigField("String", "REPOSITORY", "\"https://github.com/parham-alvani/passwords-droid\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = signingValue("storePassword", "PASSWORDS_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "PASSWORDS_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "PASSWORDS_KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Installs next to a release build, so trying a change never
            // touches the copy that holds the real keys.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes +=
            setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/INDEX.LIST",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        // JGit ships OSGi bundle metadata in every artifact, so its jars
        // collide on it. None of it means anything on Android.
        resources.pickFirsts +=
            setOf(
                "OSGI-INF/l10n/plugin.properties",
                "about.html",
                "plugin.properties",
            )
    }
}

dependencies {
    implementation(projects.core.data)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.autofill)
    implementation(libs.zxing.core)
    implementation(projects.autofillParser)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.material.icons.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
