plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.classbell.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.classbell.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 41
        versionName = "1.0.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    buildTypes { release { isMinifyEnabled = false; isDebuggable = false } }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "768m"
    maxParallelForks = 1
    jvmArgs("-XX:ActiveProcessorCount=2", "-XX:MaxMetaspaceSize=512m")
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("maven.repo.local", rootProject.file(".tools/robolectric-maven").absolutePath)
    systemProperty("user.home", rootProject.file(".tools/test-home").absolutePath)
    doFirst { rootProject.file(".tools/test-home").mkdirs() }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
