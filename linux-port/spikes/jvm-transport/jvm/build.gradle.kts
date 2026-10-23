plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(21) }
application { mainClass.set("relay.spike.MainKt") }
sourceSets.main {
    java.srcDirs("../common")
}
kotlin.sourceSets.main {
    kotlin.srcDirs("../common", rootProject.projectDir.resolve("../../../app/src/main/java"))
    kotlin.include("relay/spike/**", "Interop.kt", "com/relay/app/transport/RelayPayload.kt")
}
dependencies {
    implementation("org.rust-nostr:nostr-sdk-kmp-jvm:0.44.8")
    implementation("com.google.crypto.tink:tink:1.24.0")
    implementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
