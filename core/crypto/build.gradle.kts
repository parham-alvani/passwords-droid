plugins {
    id("passwords.jvm.library")
}

dependencies {
    api(libs.pgpainless)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
