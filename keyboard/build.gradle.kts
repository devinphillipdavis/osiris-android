plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.osiris.android.keyboard"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin", "ime", "voice", "devdeck", "privacy")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":sdk"))
    api(project(":dnalang-adapter"))
    api(project(":governance"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
