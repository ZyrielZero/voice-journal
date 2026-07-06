import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val sha = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrDefault("nogit")

android {
    namespace = "dev.zyriel.voicejournal"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.zyriel.voicejournal"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.4.1"
        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "GIT_SHA", "\"$sha\"")
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Signing config comes from an untracked keystore.properties in the
    // project root (storeFile, storePassword, keyAlias, keyPassword). The
    // key never enters the repo or CI: when the file is absent the release
    // build assembles unsigned, which is exactly what CI needs to prove R8
    // and the native build without ever seeing a secret.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasSigning = keystoreProps.containsKey("storeFile")
    if (hasSigning) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug { }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources { noCompress += "bin" }

    // The About screen renders the license texts from assets. They are
    // copied from the repo-root originals at build time so there is one
    // source of truth; nothing is hand-duplicated into assets.
}

abstract class CopyLicenseAssets : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:InputFiles
    abstract val licenseFiles: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val dest = outputDir.get().asFile.resolve("licenses")
        dest.mkdirs()
        licenseFiles.forEach { it.copyTo(dest.resolve(it.name), overwrite = true) }
    }
}

val copyLicenseAssets = tasks.register<CopyLicenseAssets>("copyLicenseAssets") {
    outputDir.set(layout.buildDirectory.dir("generated/licenseAssets"))
    licenseFiles.from(
        rootProject.file("THIRD_PARTY_NOTICES.md"),
        rootProject.file("LICENSE")
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyLicenseAssets,
            CopyLicenseAssets::outputDir
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
    // org.json is an Android framework class at runtime; JVM unit tests
    // (JournalArchiveTest) need the standalone artifact.
    testImplementation("org.json:json:20240303")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
}
