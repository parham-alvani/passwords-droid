plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint)
}

subprojects {
    // autofill-parser is vendored unchanged and keeps upstream's style.
    if (name != "autofill-parser") apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
