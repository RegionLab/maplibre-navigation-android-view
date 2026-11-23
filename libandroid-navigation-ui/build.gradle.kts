plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    namespace = "org.maplibre.navigation.android.navigation.ui.v5"

    defaultConfig {
        compileSdk = 37
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables.useSupportLibrary = true

        buildConfigField(
            "String",
            "MAPLIBRE_NAVIGATION_SDK_IDENTIFIER",
            String.format("\"%s\"", "maplibre-navigation-ui-android")
        )
        buildConfigField(
            "String",
            "MAPLIBRE_NAVIGATION_EVENTS_USER_AGENT", String.format(
                "\"maplibre-navigation-ui-android/%s\"",
                project.extra["versionName"]
            )
        )

        consumerProguardFiles("proguard-consumer.pro")
    }

    lint {
        baseline = file("lint-baseline.xml")
        // Translations are community provided, missing ones fall back to English
        warning += "MissingTranslation"
    }

    configurations.create("javadocDeps")

    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // Navigation SDK
    api(project(":maplibre-navigation-core"))

    api(libs.maplibre.annotation)
    implementation(libs.maplibre)
    implementation(libs.maplibre.turf)

    // Route requests, part of the public RoutingService API
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Support libraries
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.cardview)

    // AutoValues
    annotationProcessor(libs.autovalue)
    implementation(libs.autovalue.annotations)

    // Picasso
    implementation(libs.picasso)

    // Timber
    implementation(libs.timber)

    // Unit testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.objenesis)
}

apply {
    from(file("${rootDir}/gradle/publish-android.gradle"))
}

