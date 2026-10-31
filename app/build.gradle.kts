plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.phonemcp.android"
    compileSdk = 36
    defaultConfig { applicationId = "dev.phonemcp.android"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.1.1"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
// Runtime Android jars use the same trusted repository as Gradle. Build environments may
// supply ordinary proxy/trust settings; no certificate verification is disabled.
tasks.withType<Test>().configureEach {
    val testHome = layout.buildDirectory.dir("test-home").get().asFile
    doFirst { testHome.mkdirs() }
    systemProperty("user.home", testHome.absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    for (key in listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore")) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
