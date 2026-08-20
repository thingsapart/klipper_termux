plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.klipper.configurator"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.klipper.configurator"
        minSdk = 24
        targetSdk = 35
        versionCode = providers.gradleProperty("k4aVersionCode")
            .orElse(providers.gradleProperty("kabVersionCode")).orElse("1").get().toInt()
        versionName = providers.gradleProperty("k4aVersionName")
            .orElse(providers.gradleProperty("kabVersionName")).orElse("0.1.0").get()
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    implementation(project(":android:configurator-ui"))
}
