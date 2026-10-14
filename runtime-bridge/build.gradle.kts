plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDirs("src/main/kotlin", "termux", "proot")
        }
        test {
            kotlin.srcDirs("src/test/kotlin")
        }
    }
}

dependencies {
    api(project(":sdk"))
    api(project(":dnalang-adapter"))
    api(project(":governance"))
    testImplementation(libs.junit)
}
