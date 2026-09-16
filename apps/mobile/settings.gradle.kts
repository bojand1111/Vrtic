import java.util.Properties

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "vrtic-connect-mobile"

// :shared-core has no Android target (jvm + iOS only), so it builds and tests on any host.
include(":shared-core")

/**
 * Android modules are included only when an Android SDK can be located, so that a
 * host without the SDK (CI runner, iOS-only machine, a fresh Windows box) can still
 * run `./gradlew :shared-core:jvmTest`.
 *
 * Detection order: ANDROID_HOME, ANDROID_SDK_ROOT, then `sdk.dir` in local.properties.
 */
fun detectAndroidSdk(): File? {
    val candidates = mutableListOf<String?>()
    candidates += System.getenv("ANDROID_HOME")
    candidates += System.getenv("ANDROID_SDK_ROOT")
    val localProperties = File(rootDir, "local.properties")
    if (localProperties.isFile) {
        val props = Properties()
        localProperties.inputStream().use { props.load(it) }
        candidates += props.getProperty("sdk.dir")
    }
    return candidates
        .filterNotNull()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { File(it) }
        .firstOrNull { it.isDirectory }
}

val androidSdk = detectAndroidSdk()
if (androidSdk != null) {
    logger.lifecycle("Android SDK found at ${androidSdk.absolutePath} — including :shared-ui and :androidApp")
    include(":shared-ui")
    include(":androidApp")
} else {
    logger.warn(
        "WARNING: Android SDK not found — :shared-ui and :androidApp are skipped; " +
            ":shared-core still builds and tests. " +
            "Set ANDROID_HOME / ANDROID_SDK_ROOT or add sdk.dir to local.properties to enable them."
    )
}
