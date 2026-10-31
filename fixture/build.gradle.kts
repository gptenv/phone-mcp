plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.phonemcp.fixture"
    compileSdk = 36
    defaultConfig { applicationId = "dev.phonemcp.fixture"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "test-only" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// Never produce a distributable release variant from the synthetic test fixture.
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }
dependencies { implementation(kotlin("stdlib")) }
