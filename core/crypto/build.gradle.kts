plugins {
    id("passwords.jvm.library")
}

dependencies {
    api(libs.pgpainless)
    implementation(libs.kage) {
        // kage asks for bcprov-jdk15to18; PGPainless already brings
        // bcprov-jdk18on, which has the same classes. Two copies fail
        // Android's duplicate-class check, so kage runs on PGPainless's.
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
