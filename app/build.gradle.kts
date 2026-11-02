import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qoder.mdreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qoder.mdreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.1.4"
        resourceConfigurations += listOf("zh", "en")
    }

    val signingFile = rootProject.file(".signing/signing.properties")
    val signingProps = Properties()
    if (signingFile.isFile) signingFile.inputStream().use { signingProps.load(it) }
    fun signingValue(name: String, env: String): String? = System.getenv(env) ?: signingProps.getProperty(name)
    val releaseStore = signingValue("storeFile", "MD_RELEASE_STORE_FILE")
    val releaseStorePassword = signingValue("storePassword", "MD_RELEASE_STORE_PASSWORD")
    val releaseAlias = signingValue("keyAlias", "MD_RELEASE_KEY_ALIAS")
    val releaseKeyPassword = signingValue("keyPassword", "MD_RELEASE_KEY_PASSWORD")
    if (listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword).all { !it.isNullOrBlank() }) {
        signingConfigs.create("distribution") {
            storeFile = rootProject.file(releaseStore!!)
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isJniDebuggable = false
            vcsInfo { include = false }
            signingConfig = signingConfigs.findByName("distribution")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
}
