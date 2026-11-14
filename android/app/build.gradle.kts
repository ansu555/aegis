import java.security.MessageDigest
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
        // Optional NEAR Intents JWT (without one 1Click adds its own fee) and Ramp Network's public host key.
        buildConfigField("String", "ONECLICK_JWT", "\"${local.getProperty("tapvault.oneclick.jwt", "")}\"")
        buildConfigField("String", "RAMP_KEY", "\"${local.getProperty("tapvault.ramp.key", "")}\"")
        // Mainnet in Settings → Network: off unless local.properties says tapvault.mainnet=true
        // (keep it off until real cards have been tested and the contract has been reviewed).
        buildConfigField("boolean", "MAINNET_ENABLED", local.getProperty("tapvault.mainnet", "false").trim().equals("true", ignoreCase = true).toString())
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

// The app deploys the wallet contract itself, so it ships the wasm: exactly
// the reviewed build committed in contract/res, checked by its SHA-256
// (base58 DdhpDh3c2gbVJK4iFQLU7C3c2W4Bgg6JYTBSBtBDRcS7, as in the README).
val contractWasm = rootProject.file("../contract/res/tapvault_contract.wasm")
val contractWasmSha256 = "bbb34d0898d18ab7bb8687c61ba8605def6e7c9a33be91659b199d0834ca2f1c"

val verifyWasm by tasks.registering {
    doLast {
        check(contractWasm.exists()) { "Missing ${contractWasm.path}: it is committed to the repo; restore it with git." }
        val hash = MessageDigest.getInstance("SHA-256").digest(contractWasm.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(hash == contractWasmSha256) {
            "contract/res/tapvault_contract.wasm has SHA-256 $hash, expected $contractWasmSha256. " +
                "If you changed the contract on purpose, update contractWasmSha256 here and the hash in README.md."
        }
    }
}

val copyWasm by tasks.registering(Copy::class) {
    dependsOn(verifyWasm)
    from(contractWasm)
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
    implementation("com.google.zxing:core:3.5.3") // Receive QR code
    implementation("dev.chrisbanes.haze:haze:1.5.4") // backdrop blur for the glass tab bar, sheets and banners (Compose 1.7 / Kotlin 2.1 build)
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
