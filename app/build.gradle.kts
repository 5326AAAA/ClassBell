import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

abstract class BuildScanWasm @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val goSource: DirectoryProperty

    @get:Internal
    abstract val goInstall: DirectoryProperty

    @get:Input
    abstract val executableName: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val goBuildFiles get() = goInstall.get().asFileTree.matching {
        include("bin/${executableName.get()}", "lib/wasm/wasm_exec.js", "VERSION", "LICENSE")
    }

    @get:OutputDirectory
    abstract val generatedAssets: DirectoryProperty

    @TaskAction
    fun compile() {
        val install = goInstall.get().asFile
        val go = install.resolve("bin/${executableName.get()}")
        val runtime = install.resolve("lib/wasm/wasm_exec.js")
        check(go.isFile && runtime.isFile) { "Install Go 1.24+ in .tools/go or set -PclassbellGoHome to its installation directory." }
        val out = generatedAssets.get().asFile.apply { mkdirs() }
        execOperations.exec {
            workingDir(goSource.get().asFile)
            executable(go.absolutePath)
            environment(mapOf("GOROOT" to install.absolutePath, "GOTOOLCHAIN" to "local", "GOWORK" to "off",
                "GOENV" to "off", "GOFLAGS" to "", "CGO_ENABLED" to "0", "GOOS" to "js", "GOARCH" to "wasm"))
            args("build", "-trimpath", "-buildvcs=false", "-ldflags=-s -w", "-o", out.resolve("classbell-scan.wasm").absolutePath, ".")
        }
        runtime.copyTo(out.resolve("wasm_exec.js"), overwrite = true)
        install.resolve("LICENSE").copyTo(out.resolve("licenses/go-wasm.txt").also { it.parentFile.mkdirs() }, overwrite = true)
    }
}

val scanWasm = tasks.register<BuildScanWasm>("buildClassBellScanWasm") {
    val toolRoot = providers.gradleProperty("classbellGoHome").orElse(providers.environmentVariable("GOROOT"))
    goInstall.set(rootProject.layout.dir(toolRoot.map { rootProject.file(it) }).orElse(rootProject.layout.projectDirectory.dir(".tools/go")))
    goSource.set(layout.projectDirectory.dir("src/main/go"))
    executableName.set(if (System.getProperty("os.name").startsWith("Windows")) "go.exe" else "go")
    generatedAssets.set(layout.buildDirectory.dir("generated/classbellScanAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(scanWasm, BuildScanWasm::generatedAssets)
    }
}

android {
    namespace = "cn.classbell.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.classbell.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 47
        versionName = "1.3.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    buildTypes { release { isMinifyEnabled = false; isDebuggable = false } }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // Opt in without changing the filenames expected by existing build scripts.
    splits {
        abi {
            isEnable = providers.gradleProperty("classbellAbiSplits").orNull == "true"
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
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
    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    // Bundled model for real-time camera scanning and gallery fallback.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
