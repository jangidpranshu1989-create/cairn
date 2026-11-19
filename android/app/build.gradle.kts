import java.util.Properties
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// UI screenshot tests (JVM, no emulator): ./gradlew -Pshots :app:recordPaparazziDebug
if (project.hasProperty("shots")) apply(plugin = "app.cash.paparazzi")

// Release signing: put keystore details in android/keystore.properties (never commit it)
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties"); if (f.exists()) f.inputStream().use { load(it) }
}

// Built-in AI keys, so Cairn works right after install with no setup. Supplied at build time from
// env vars / Gradle properties GROQ_API_KEY and NVIDIA_API_KEY (CI: repository secrets) or android/keys.properties
// (never committed). They are XOR-masked in the APK so they don't show up in a plain `strings` dump.
val keyProps = Properties().apply {
    val f = rootProject.file("keys.properties"); if (f.exists()) f.inputStream().use { load(it) }
}
fun builtInKey(name: String): String {
    val v = (System.getenv(name) ?: (project.findProperty(name) as String?) ?: keyProps.getProperty(name) ?: "").trim()
    if (v.isEmpty()) return ""
    val salt = "cairn-stones-v2".toByteArray()
    val x = v.toByteArray().mapIndexed { i, b -> (b.toInt() xor salt[i % salt.size].toInt()).toByte() }.toByteArray()
    return Base64.getEncoder().encodeToString(x)
}

android {
    namespace = "app.cairn.browser"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.cairn.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.0.0"
        // Default Cairn Cloud server; users can change it in Settings → Account (pairing QR codes carry it automatically)
        buildConfigField("String", "DEFAULT_SERVER", "\"https://api.cairn.app\"")
        buildConfigField("String", "SITE", "\"https://cairn.app\"")
        buildConfigField("String", "K_GROQ", "\"${builtInKey("GROQ_API_KEY")}\"")
        buildConfigField("String", "K_NVIDIA", "\"${builtInKey("NVIDIA_API_KEY")}\"")
    }

    signingConfigs {
        if (ksProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(ksProps["storeFile"] as String)
            storePassword = ksProps["storePassword"] as String
            keyAlias = ksProps["keyAlias"] as String
            keyPassword = ksProps["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
