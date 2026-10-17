plugins {
    id("passwords.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "me.parham1995.passwords.data"

    testOptions {
        unitTests {
            // DataStore and the git transport want a real Context; Robolectric
            // supplies one on the JVM, which keeps the suite emulator-free.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(projects.core.store)
    api(projects.core.crypto)

    implementation(libs.androidx.core.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    // A push that fails is retried in the background by a Hilt-built worker.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // git-over-SSH.
    implementation(libs.jgit)
    implementation(libs.jgit.ssh.apache)
    // sshd carries EdDSA support classes but no implementation; without this
    // Ed25519 is reported unsupported and key generation silently produces a
    // different algorithm.
    implementation(libs.eddsa)
    // Not optional: JGit and sshd call LoggerFactory on their own code paths.
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.slf4j.simple)
}
