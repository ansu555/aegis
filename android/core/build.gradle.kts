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
    environment("TAPVAULT_WASM", rootProject.file("../target/near/tapvault_contract/tapvault_contract.wasm").absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
