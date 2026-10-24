plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "relay.spike.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "relay.spike.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["main"].java.srcDirs("../common", layout.buildDirectory.dir("generated/production-codec"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
val copyProductionCodec by tasks.registering(Sync::class) {
    from(rootProject.projectDir.resolve("../../../core/src/main/kotlin")) { include("com/relay/app/transport/RelayPayload.kt") }
    into(layout.buildDirectory.dir("generated/production-codec"))
}
tasks.named("preBuild") { dependsOn(copyProductionCodec) }
dependencies {
    implementation("org.rust-nostr:nostr-sdk-kmp-android:0.44.8")
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
