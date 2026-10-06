plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.commons.compress)
    testImplementation(libs.junit)
}
