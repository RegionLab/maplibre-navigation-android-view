plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlinx.serialization)
}

apply {
    from(file("${rootDir}/gradle/artifact-settings.gradle"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "org.maplibre.navigation.android.example"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "org.maplibre.navigation.android.example"
        compileSdk = 37
        minSdk = 23
        // Android 15 refuses to install apps targeting SDK 23 or lower
        targetSdk = 36

        versionCode = 1
        versionName = project.properties.get("versionName") as String? ?: "0.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = false
        }

        release {
            isMinifyEnabled = true
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":libandroid-navigation-ui"))
    implementation(project(":maplibre-navigation-tts-sherpa"))

    implementation(libs.maplibre)

    // Support libraries
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.cardview)

    // Logging
    implementation(libs.timber)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Leak Canary
    implementation(libs.leakcanary)

    implementation(libs.androidx.lifecycle.viewmodel)
}

apply {
    from(file("${rootDir}/gradle/developer-config.gradle"))
}
