import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------------------------
// Release signing.
// The key comes from  keystore.properties  (in the project root, NEVER commit it)  or from the
// DUSTLINE_* environment variables that the GitHub Actions workflow sets.
// Without a key the release APK/AAB is still built, but UNSIGNED - Play Console rejects that.
// ---------------------------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

fun signingValue(prop: String, env: String): String? =
    (keystoreProps.getProperty(prop) ?: System.getenv(env))?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFile: String? = signingValue("storeFile", "DUSTLINE_KEYSTORE_FILE")
val releaseStorePassword: String? = signingValue("storePassword", "DUSTLINE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = signingValue("keyAlias", "DUSTLINE_KEY_ALIAS")
val releaseKeyPassword: String? = signingValue("keyPassword", "DUSTLINE_KEY_PASSWORD") ?: releaseStorePassword

val hasReleaseSigning: Boolean =
    releaseStorePassword != null && releaseKeyAlias != null &&
        releaseStoreFile?.let { rootProject.file(it).exists() } == true

if (!hasReleaseSigning) {
    logger.warn(
        "WARNING: no release keystore configured (keystore.properties / DUSTLINE_KEYSTORE_* env). " +
            "assembleRelease / bundleRelease will be UNSIGNED. See PLAY_STORE/BUILD_AND_UPLOAD.md."
    )
}

android {
    namespace = "com.dustline.game"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dustline.battleroyale3d"   // namespace (Kotlin package) stays com.dustline.game
        minSdk = 24
        targetSdk = 36          // Google Play: new apps and updates must target API 36 (Android 16) since 2026-08-31
        versionCode = 46        // +1 for every upload to Play Console
        versionName = "1.0.14"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // plain-Kotlin game, no reflection - kept off so nothing can be stripped by mistake
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // bin / json / jpg / png / txt / gun assets are stored uncompressed: faster to read, and they are already compact.
    androidResources {
        noCompress += listOf("bin", "json", "jpg", "png", "txt", "gun")
    }

    lint {
        abortOnError = false      // lint still runs and reports; a warning must not block the first release build
        checkReleaseBuilds = false // lintVital on assembleRelease / bundleRelease can run out of memory on this big project and fail the build for no real reason
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // AdMob rewarded video (lobby WATCH VIDEO = free diamonds).
    // 25.5.0 = newest "legacy" Google Mobile Ads SDK (2026-09-17). 23.x is already DEPRECATED (sunset 2027-06-30), 24.x/25.x are supported.
    // Google calls this SDK "maintenance mode": plan the move to the GMA Next-Gen SDK before 2027-06-30 (see PLAY_STORE/ADS_AND_PRIVACY_CHECKLIST.md).
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    // Google UMP (User Messaging Platform): the consent form that AdMob requires for users in the EEA / UK / Switzerland.
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
}
