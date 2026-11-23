import java.util.Properties

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

// AI provider keys live only on the Cairn server. Nothing secret is compiled into the APK.

android {
    namespace = "app.cairn.browser"
    // Plain JVM unit tests (payment gate, file chooser, shopping intent) live in src/testLogic and always run;
    // the Paparazzi UI shots in src/test only compile when -Pshots is set.
    sourceSets.getByName("test").java.setSrcDirs(listOf("src/testLogic/java") + if (project.hasProperty("shots")) listOf("src/test/java") else emptyList())
    testOptions.unitTests.isReturnDefaultValues = true
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.cairn.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "3.6.1"
        // Default Cairn Cloud server; users can change it in Settings → Account (pairing QR codes carry it automatically)
        buildConfigField("String", "DEFAULT_SERVER", "\"${(project.findProperty("cairnServer") as String?) ?: "https://cairn-cloud.onrender.com"}\"")
        buildConfigField("String", "SITE", "\"${(project.findProperty("cairnSite") as String?) ?: "https://jangidpranshu1989-create.github.io/cairn"}\"")
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
            // Never ship a release signed with the public debug key. CI without keystore secrets still builds an
            // unsigned release APK; set CAIRN_ALLOW_DEBUG_SIGNING=1 only for local test installs.
            signingConfig = signingConfigs.findByName("release")
                ?: if (System.getenv("CAIRN_ALLOW_DEBUG_SIGNING") == "1") signingConfigs.getByName("debug") else null
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
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
}
