// Relay desktop client (WP06): Compose Multiplatform on the shared :core protocol module.
// Kotlin 2.0.21 / JVM target 17, matching :app and :core. Linux x86_64 is the first target.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "com.relay.app"
version = "0.1.0"

kotlin {
    // The host JDK is 21; the bytecode target stays 17 like :core and :app.
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))

    // Compose Multiplatform desktop (same Compose 1.7.x line; Kotlin 2.0.21 compatible).
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Dispatchers.Main on the AWT event thread.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")

    // WP02-proven API + Linux native classifier; POM has a classifier self-dependency.
    implementation("dev.onvoid.webrtc:webrtc-java:0.20.0") { isTransitive = false }
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.20.0:linux-x86_64") { isTransitive = false }

    // :core declares org.json compileOnly (the Android platform provides it); the desktop supplies it.
    implementation("org.json:json:20240303")

    // Same wire stack as the phone: OkHttp 4.12.0 for HTTP and the mailbox WebSocket.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // The exact JVM artifact proven in linux-port/spikes/jvm-transport.
    implementation("org.rust-nostr:nostr-sdk-kmp-jvm:0.44.8")

    // Tink JVM 1.24.0 is the spike-proven HPKE implementation; :core itself stays Tink-free.
    implementation("com.google.crypto.tink:tink:1.24.0")

    // SQLCipher-capable SQLite for the desktop. The Willena fork bundles SQLite3 Multiple Ciphers
    // (SQLCipher-compatible modes) in one jar; see linux-port/evidence/WP06.md for licence and why.
    implementation("io.github.willena:sqlite-jdbc:3.50.1.0")

    // LP-D05: OS secret store (Linux Secret Service / macOS Keychain) via java-keyring.
    implementation("com.github.javakeyring:java-keyring:1.0.4")

    // QR rendering for the "Link this computer" screen (WP05 part B). ZXing core is plain Java,
    // Apache-2.0, no native code; it only produces a BitMatrix that Compose draws as rectangles,
    // so no image pipeline or AWT surface is involved. 3.5.4 was already in the Gradle cache.
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    // WP07-AC3: real Compose UI tests (semantics tree + key/mouse input) for the desktop window.
    testImplementation(compose.desktop.uiTestJUnit4)
}

compose.desktop {
    application {
        mainClass = "com.relay.app.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "relay"
            packageVersion = "0.1.0"
            description = "Relay desktop client"
            vendor = "Relay"
            // jpackage's suggested module set misses java.sql even though DesktopMessageStore uses
            // JDBC directly; without it the packaged app dies with NoClassDefFoundError.
            modules("java.sql")
        }
    }
}

tasks.withType<Test>().configureEach {
    // Keep tests hermetic: no real Secret Service, no user XDG directories.
    systemProperty("relay.desktop.test", "true")
    maxHeapSize = "1g"
}
