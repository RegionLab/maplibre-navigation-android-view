import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

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

// sherpa-onnx is only distributed as an AAR on GitHub. It is downloaded and verified at build
// time instead of being committed, its Kotlin API and native libraries are bundled into this
// module's AAR.
// 1.x on purpose: sherpa-onnx 2.x removed the built-in espeak-ng phonemizer that Piper voices
// need, they fail with "Failed to convert ... to token IDs" there.
val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxSha256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
val sherpaOnnxUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/" +
    "sherpa-onnx-static-link-onnxruntime-$sherpaOnnxVersion.aar"
// ABIs to bundle, e.g. -PsherpaAbis=arm64-v8a for the smallest size. Each ABI adds 16–27 MB.
val sherpaAbis = (findProperty("sherpaAbis") as String? ?: "arm64-v8a,armeabi-v7a,x86_64")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

val sherpaDir = layout.buildDirectory.dir("sherpa-onnx")
val sherpaClassesJar = sherpaDir.map { it.file("classes.jar") }
val sherpaJniLibs = sherpaDir.map { it.dir("jniLibs") }

val prepareSherpaOnnx by tasks.registering {
    description = "Downloads the sherpa-onnx AAR and extracts its classes and native libraries."
    inputs.property("url", sherpaOnnxUrl)
    inputs.property("sha256", sherpaOnnxSha256)
    inputs.property("abis", sherpaAbis)
    outputs.dir(sherpaDir)
    doLast {
        val dir = sherpaDir.get().asFile
        val aar = File(dir, "sherpa-onnx-$sherpaOnnxVersion.aar")
        dir.mkdirs()
        if (!aar.exists() || sha256(aar) != sherpaOnnxSha256) {
            URI(sherpaOnnxUrl).toURL().openStream().use { input -> aar.outputStream().use { input.copyTo(it) } }
        }
        val actualSha256 = sha256(aar)
        check(actualSha256 == sherpaOnnxSha256) {
            "sherpa-onnx AAR checksum mismatch: expected $sherpaOnnxSha256, got $actualSha256"
        }
        val jniDir = sherpaJniLibs.get().asFile
        jniDir.deleteRecursively()
        ZipFile(aar).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val target = when {
                    entry.name == "classes.jar" -> sherpaClassesJar.get().asFile
                    entry.name.startsWith("jni/") && entry.name.endsWith(".so") &&
                        entry.name.split("/")[1] in sherpaAbis -> File(jniDir, entry.name.removePrefix("jni/"))
                    else -> null
                } ?: return@forEach
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes())
    .joinToString("") { "%02x".format(it) }

android {
    namespace = "org.maplibre.navigation.android.tts.sherpa"

    defaultConfig {
        compileSdk = 36
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            // A plain path, the Android source set API doesn't accept providers
            jniLibs.srcDir(layout.buildDirectory.dir("sherpa-onnx/jniLibs").get().asFile)
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

tasks.named("preBuild") { dependsOn(prepareSherpaOnnx) }

dependencies {
    api(project(":libandroid-navigation-ui"))
    implementation(files(sherpaClassesJar).builtBy(prepareSherpaOnnx))
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.objenesis)
    testImplementation(libs.robolectric)
}

apply {
    from(file("${rootDir}/gradle/publish-android.gradle"))
}
