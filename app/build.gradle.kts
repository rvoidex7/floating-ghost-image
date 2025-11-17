plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rvoidex7.floatingghostimage"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rvoidex7.floatingghostimage"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only include necessary densities to reduce APK size
        // Remove xxxhdpi and xxhdpi to save ~200KB
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            // Signing config loaded from gradle.properties (gitignored) or environment variables
            // Never commit passwords to version control!
            val keystorePath = project.findProperty("RELEASE_KEYSTORE_PATH") as String?
            val storePass = project.findProperty("RELEASE_STORE_PASSWORD") as String?
            val alias = project.findProperty("RELEASE_KEY_ALIAS") as String?
            val keyPass = project.findProperty("RELEASE_KEY_PASSWORD") as String?

            if (keystorePath != null && storePass != null && alias != null && keyPass != null) {
                storeFile = file(keystorePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            } else {
                // Release signing not configured - will use debug signing
                println("WARNING: Release signing config not found. Set properties in gradle.properties:")
                println("  RELEASE_KEYSTORE_PATH=path/to/keystore")
                println("  RELEASE_STORE_PASSWORD=your_password")
                println("  RELEASE_KEY_ALIAS=your_alias")
                println("  RELEASE_KEY_PASSWORD=your_password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Disable debugging for smaller APK
            isDebuggable = false
            // Disable JNI debugging
            isJniDebuggable = false
        }
    }

    // Build features - disable unused features
    buildFeatures {
        buildConfig = true
        viewBinding = false
        dataBinding = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}