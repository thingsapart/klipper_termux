plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.klipper.androidbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.klipper.androidbridge"
        minSdk = 24
        targetSdk = 35
        versionCode = providers.gradleProperty("k4aVersionCode")
            .orElse(providers.gradleProperty("kabVersionCode")).orElse("1").get().toInt()
        versionName = providers.gradleProperty("k4aVersionName")
            .orElse(providers.gradleProperty("kabVersionName")).orElse("0.1.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val installerUrl = providers.gradleProperty("k4aInstallerUrl")
            .orElse(providers.gradleProperty("kabInstallerUrl")).orElse(
            "https://raw.githubusercontent.com/OWNER/REPOSITORY/main/installer/install.sh",
        ).get()
        val repositoryUrl = providers.gradleProperty("k4aRepositoryUrl")
            .orElse(providers.gradleProperty("kabRepositoryUrl")).orElse(
            "https://github.com/OWNER/REPOSITORY.git",
        ).get()
        val termuxDownloadUrl = providers.gradleProperty("k4aTermuxDownloadUrl")
            .orElse(providers.gradleProperty("kabTermuxDownloadUrl")).orElse(
            "https://f-droid.org/packages/com.termux/",
        ).get()
        val termuxGithubReleasesUrl = providers.gradleProperty("k4aTermuxGithubReleasesUrl")
            .orElse(providers.gradleProperty("kabTermuxGithubReleasesUrl")).orElse(
            "https://github.com/termux/termux-app/releases",
        ).get()
        buildConfigField("String", "K4A_INSTALLER_URL", "\"${installerUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "K4A_REPOSITORY_URL", "\"${repositoryUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "TERMUX_DOWNLOAD_URL", "\"${termuxDownloadUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "TERMUX_GITHUB_RELEASES_URL", "\"${termuxGithubReleasesUrl.replace("\"", "\\\"")}\"")
    }

    buildFeatures.buildConfig = true

    val releaseSigningValues = listOf(
        "K4A_SIGNING_STORE_FILE",
        "K4A_SIGNING_STORE_PASSWORD",
        "K4A_SIGNING_KEY_ALIAS",
        "K4A_SIGNING_KEY_PASSWORD",
    ).associateWith {
        providers.environmentVariable(it)
            .orElse(providers.environmentVariable(it.replace("K4A_", "KAB_"))).orNull
    }
    val hasReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }
    val useDebugSigning = providers.gradleProperty("k4aUseDebugSigning")
        .orElse(providers.gradleProperty("kabUseDebugSigning")).orNull == "true"

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("K4A_SIGNING_STORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("K4A_SIGNING_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("K4A_SIGNING_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("K4A_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = when {
                useDebugSigning -> signingConfigs.getByName("debug")
                hasReleaseSigning -> signingConfigs.getByName("release")
                else -> null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":android:configurator-ui"))
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    testImplementation("junit:junit:4.13.2")
}
