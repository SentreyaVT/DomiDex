import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// Opt-in only after the Firebase/Play registration and rollout checks are complete.
val appCheckEnabled = providers.gradleProperty("domidexAppCheck").orNull?.let {
    require(it == "true" || it == "false") { "domidexAppCheck must be true or false" }
    it.toBoolean()
} ?: false

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties()
if (releaseKeystorePropertiesFile.isFile) {
    releaseKeystorePropertiesFile.inputStream().use(releaseKeystoreProperties::load)
}
// The Windows release helper decrypts credentials only for its short-lived build
// process. No plaintext signing properties or passwords need to live in the repo.
val releaseSigningEnvironment = mapOf(
    "storeFile" to "DOMIDEX_UPLOAD_STORE_FILE",
    "storePassword" to "DOMIDEX_UPLOAD_STORE_PASSWORD",
    "keyAlias" to "DOMIDEX_UPLOAD_KEY_ALIAS",
    "keyPassword" to "DOMIDEX_UPLOAD_KEY_PASSWORD",
)
releaseSigningEnvironment.forEach { (property, environment) ->
    providers.environmentVariable(environment).orNull?.takeIf { it.isNotBlank() }?.let {
        releaseKeystoreProperties.setProperty(property, it)
    }
}
val releaseSigningConfigured = releaseKeystorePropertiesFile.isFile ||
    releaseSigningEnvironment.keys.any { !releaseKeystoreProperties.getProperty(it).isNullOrBlank() }

if (releaseSigningConfigured) {
    val requiredKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val missingKeys = requiredKeys.filter { releaseKeystoreProperties.getProperty(it).isNullOrBlank() }
    require(missingKeys.isEmpty()) {
        "Release signing is missing required values: ${missingKeys.joinToString()}"
    }
    val configuredStore = rootProject.file(releaseKeystoreProperties.getProperty("storeFile").trim())
    require(configuredStore.isFile) { "Release keystore does not exist: $configuredStore" }
    require(!configuredStore.name.equals("debug.keystore", ignoreCase = true)) {
        "The Android debug keystore must never sign a DomiDex release."
    }
}

android {
    namespace = "com.dominik.cardvault"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dominik.cardvault"
        minSdk = 26
        targetSdk = 37
        versionCode = 27
        versionName = "1.0-closed-alpha.6"
        buildConfigField("boolean", "DOMIDEX_APP_CHECK_ENABLED", appCheckEnabled.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile").trim())
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Optional only for restricted CI/sandbox verification; Android Studio keeps its
            // normal installable debug signature when this property is absent.
            if (providers.gradleProperty("unsignedDebug").isPresent) signingConfig = null
        }
        release {
            // Private tester releases target current physical Android phones.
            // Keeping emulator/x86 native libraries out of this artifact cuts
            // the download substantially; debug builds still support the AVD.
            // No local credentials means an intentionally unsigned build artifact. There is
            // never a fallback to the Android debug key for anything named "release".
            signingConfig = signingConfigs.findByName("release")
            ndk {
                abiFilters += "arm64-v8a"
            }
            // First R8 rollout: generate a real retrace mapping, without removing
            // code/resources or rewriting executable logic (see release.keep).
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.moshi)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.opencv)
    implementation(libs.mlkit.text.latin)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.config)
    implementation(libs.firebase.appcheck)
    debugImplementation(libs.firebase.appcheck.debug)
    releaseImplementation(libs.firebase.appcheck.play.integrity)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.app.update)
    implementation(libs.androidx.window)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestUtil(libs.androidx.test.orchestrator)
    // Room 2.8.4's migration bundle serializers use kotlinx-serialization 1.8.1.
    // Keep core and json on the same runtime to avoid an AbstractMethodError on-device.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
