import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.tapvault"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.tapvault"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Optional testnet sponsor (see README), read from the untracked local.properties.
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "SPONSOR_ACCOUNT", "\"${local.getProperty("tapvault.sponsor.account", "")}\"")
        buildConfigField("String", "SPONSOR_KEY", "\"${local.getProperty("tapvault.sponsor.key", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Testnet demo build: signed with the debug key so it installs anywhere.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

// The app deploys the wallet contract itself, so it ships the wasm.
val copyWasm by tasks.registering(Copy::class) {
    val wasm = rootProject.file("../target/near/tapvault_contract/tapvault_contract.wasm")
    doFirst {
        check(wasm.exists()) { "Build the contract first: cd contract && cargo near build non-reproducible-wasm" }
    }
    from(wasm)
    into(file("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(copyWasm) }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
