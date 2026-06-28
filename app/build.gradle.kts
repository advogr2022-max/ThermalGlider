plugins {
    id("com.android.application")
}

android {
    namespace = "com.thermalglider"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thermalglider"
        minSdk = 26
        targetSdk = 34
        versionCode = 103
        versionName = "1.0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.0.1")
}
