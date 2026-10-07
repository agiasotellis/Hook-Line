plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.hookline.radar"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.hookline.radar"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.0"
        // NWS asks every client to identify itself with contact info.
        buildConfigField("String", "NWS_USER_AGENT", "\"HooklineRadar/0.1 (support@hookline.app)\"")
    }

    // Release signing: the upload key comes from CI secrets (see docs/PLAY-RELEASE.md).
    // Without them, release builds are signed with the debug key so CI can still verify R8.
    val keystore = System.getenv("HOOKLINE_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("upload") {
                storeFile = keystore
                storePassword = System.getenv("HOOKLINE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HOOKLINE_KEY_ALIAS")
                keyPassword = System.getenv("HOOKLINE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystore != null) signingConfigs.getByName("upload") else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // One APK per CPU type keeps test downloads small (MapLibre ships native code).
    // Play Store releases use an App Bundle, which splits automatically.
    splits {
        abi {
            // App bundles split by CPU on Google Play's side; per-ABI APK splits clash with bundle resource shrinking.
            isEnable = gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:radar"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.play.billing)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.maplibre.android)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
