plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tools.wp2.sideloader"
    compileSdk = 36

    defaultConfig {
        applicationId = "tools.wp2.sideloader"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    val releaseKeystore = System.getenv("WP2_STORE_FILE")?.let { file(it) }
    val hasReleaseSigning = releaseKeystore?.exists() == true &&
        !System.getenv("WP2_STORE_PASS").isNullOrEmpty() &&
        !System.getenv("WP2_KEY_ALIAS").isNullOrEmpty() &&
        !System.getenv("WP2_KEY_PASS").isNullOrEmpty()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("WP2_STORE_PASS")
                keyAlias = System.getenv("WP2_KEY_ALIAS")
                keyPassword = System.getenv("WP2_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.json)
}
