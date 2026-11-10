plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Live testnet run: TAPVAULT_TESTNET=1 ./gradlew :core:test --tests '*TestnetE2E*'
    // Optional: a funded testnet account used when the faucet is rate-limited.
    listOf("TAPVAULT_SPONSOR_ACCOUNT", "TAPVAULT_SPONSOR_KEY", "TAPVAULT_FORCE_SPONSOR").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
    environment("TAPVAULT_WASM", rootProject.file("../target/near/tapvault_contract/tapvault_contract.wasm").absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
