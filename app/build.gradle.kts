import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Sign-in settings: local.properties (git-ignored) or a Gradle property of the same name.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signInSetting(key: String, default: String): String =
    (localProps.getProperty(key) ?: providers.gradleProperty(key).orNull ?: default).trim()
fun String.asBuildConfigString() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.financeos.smssync"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.financeos.smssync"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        buildConfigField("String", "SITE_URL", signInSetting("financeos.siteUrl", "https://financeos-client.vercel.app").asBuildConfigString())
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", signInSetting("financeos.googleWebClientId", "").asBuildConfigString())
    }

    buildTypes {
        release {
            // Sideload only. Signed with the debug key: that is the key registered for Google sign-in.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime:2.10.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.work:work-testing:2.10.0")
}

// Screenshot test output folder: ./gradlew testDebugUnitTest -PshotsDir=/some/dir (defaults to app/build/shots).
tasks.withType<Test>().configureEach {
    systemProperty(
        "shotsDir",
        providers.gradleProperty("shotsDir").orElse(layout.buildDirectory.dir("shots").map { it.asFile.absolutePath }).get(),
    )
}
