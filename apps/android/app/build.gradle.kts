plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val repositoryRoot = rootProject.projectDir.parentFile.parentFile
val piWebViewAssets = repositoryRoot.resolve("dist/pi-webview")
val piQuickJsAssets = repositoryRoot.resolve("dist/pi-quickjs")
val bundlePiWebView by tasks.registering(Exec::class) {
    workingDir(repositoryRoot)
    commandLine("npm", "run", "pi:bundle:webview")
    inputs.files(repositoryRoot.resolve("src/pi").walkTopDown().filter { it.isFile }.toList())
    inputs.file(repositoryRoot.resolve("scripts/build-pi-webview.mjs"))
    inputs.file(repositoryRoot.resolve("package-lock.json"))
    outputs.file(piWebViewAssets.resolve("pi-mobile-runtime.js"))
    outputs.file(piQuickJsAssets.resolve("pi-mobile-quickjs-runtime.js"))
}

android {
    namespace = "ai.mobileagent"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.mobileagent"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(piWebViewAssets)
    sourceSets["main"].assets.srcDir(piQuickJsAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.github.dokar3:quickjs-kt:1.0.12")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild").configure { dependsOn(bundlePiWebView) }
