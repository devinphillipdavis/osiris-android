plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":sdk"))
    api(project(":dnalang-adapter"))
    testImplementation(libs.junit)
}
