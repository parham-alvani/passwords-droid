plugins {
    id("passwords.jvm.library")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxcvbn)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
