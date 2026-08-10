plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.nodeterm.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nodeterm.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // sshj pulls in all three BouncyCastle jars; each ships the same OSGi manifest.
            excludes += "/META-INF/versions/**/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Node-kind icons (terminal / agent / note / group / editor / diff / web / video) — the
    // desktop's visual language. Unused glyphs are stripped by R8 in release builds.
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)

    // FCM entry — works WITHOUT google-services.json (the service is simply dormant until a
    // Firebase project is wired; see README). Add the google-services plugin + a
    // google-services.json to enable real delivery.
    implementation(libs.firebase.messaging)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // LAN / SSH direct transport (free tier — no relay / Pro needed).
    // sshj for the ed25519 client; bcprov provides the JCE algorithms Android lacks;
    // slf4j-nop silences sshj's logger (no Android backend pulled in). eddsa comes via :core (api).
    implementation(libs.sshj)
    implementation(libs.bcprov)
    implementation(libs.slf4j.nop)

    testImplementation(libs.junit)
}
