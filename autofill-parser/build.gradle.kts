/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: LGPL-3.0-only WITH LGPL-3.0-linking-exception
 */

// Vendored from Password Store (github.com/agrahn/Android-Password-Store,
// autofill-parser/) unchanged, so updates can be copied over wholesale. It
// keeps its own code style and warnings; this build's stricter settings are
// relaxed here and nowhere else.
plugins {
    id("passwords.android.library")
}

android {
    namespace = "com.github.androidpasswordstore.autofillparser"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    sourceSets { getByName("test") { resources.directories.add("src/main/assets") } }
    lint {
        warningsAsErrors = false
        abortOnError = false
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions { allWarningsAsErrors.set(false) }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.thirdparty.logcat)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
