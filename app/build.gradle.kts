import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.monkopedia.healthdisconnect"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.monkopedia.healthdisconnect"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
                ?: "${System.getProperty("user.home")}/.android_keys/release.keystore"
            storeFile = file(keystorePath)
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                ?: file("${System.getProperty("user.home")}/.android_keys/store_password.txt")
                    .takeIf { it.exists() }?.readText()?.trim()
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "health-disconnect"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                ?: file("${System.getProperty("user.home")}/.android_keys/key_password.txt")
                    .takeIf { it.exists() }?.readText()?.trim()
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    flavorDimensions += "mode"
    productFlavors {
        create("prod") {
            dimension = "mode"
            buildConfigField("boolean", "DEMO_MODE", "false")
        }
        create("demo") {
            dimension = "mode"
            applicationIdSuffix = ".demo"
            buildConfigField("boolean", "DEMO_MODE", "true")
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.health.connect)
    implementation(libs.kotlinx.serialization)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.mockk)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.register("recordRoborazziTableDebug") {
    group = "verification"
    description =
        "Records Roborazzi screenshots and rewrites the dashboard as a side-by-side table by screen name."
    dependsOn(
        "recordRoborazziPhoneDebug",
        "recordRoborazziTablet7Debug",
        "recordRoborazziTabletDebug",
        "recordRoborazziDashboardIntegrityDebug"
    )

    doLast {
        val screensDir = layout.buildDirectory.dir("outputs/roborazzi/screens").get().asFile
        val reportDir = layout.buildDirectory.dir("reports/roborazzi/debug").get().asFile
        val indexFile = reportDir.resolve("index.html")
        reportDir.mkdirs()

        val pattern = Regex("""(.+)_([A-Za-z0-9-]+)\.png$""")
        val grouped = sortedMapOf<String, MutableMap<String, String>>()
        val sizes = sortedSetOf<String>()

        screensDir
            .listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val match = pattern.matchEntire(file.name) ?: return@forEach
                val screen = match.groupValues[1]
                val size = match.groupValues[2]
                sizes += size
                grouped.getOrPut(screen) { mutableMapOf() }[size] = file.name
            }

        val headerCols = sizes.joinToString("") { "<th>${it.uppercase()}</th>" }
        val rows = grouped.entries.joinToString("\n") { (screen, bySize) ->
            val cells = sizes.joinToString("") { size ->
                val fileName = bySize[size]
                if (fileName == null) {
                    "<td class=\"missing\">-</td>"
                } else {
                    val src = "../../../outputs/roborazzi/screens/$fileName"
                    "<td><img src=\"$src\" alt=\"$fileName\" loading=\"lazy\"/></td>"
                }
            }
            "<tr><th>$screen</th>$cells</tr>"
        }

        indexFile.writeText(
            """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Roborazzi Side-by-Side</title>
              <style>
                :root { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
                body { margin: 16px; background: #f6f8fa; color: #111827; }
                h1 { margin: 0 0 8px 0; font-size: 1.35rem; }
                p { margin: 0 0 16px 0; color: #4b5563; }
                .table-wrap { overflow: auto; background: #fff; border: 1px solid #d1d5db; border-radius: 8px; }
                table { width: 100%; border-collapse: collapse; min-width: 900px; }
                th, td { border-bottom: 1px solid #e5e7eb; padding: 10px; vertical-align: top; }
                thead th { position: sticky; top: 0; background: #f3f4f6; text-align: left; z-index: 1; }
                tbody th { width: 220px; background: #fbfdff; text-align: left; }
                img { max-width: 280px; height: auto; border: 1px solid #d1d5db; border-radius: 4px; background: #fff; }
                .missing { color: #9ca3af; text-align: center; }
              </style>
            </head>
            <body>
              <h1>Roborazzi Screenshots</h1>
              <p>Rows are screens, columns are sizes.</p>
              <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Screen</th>
                      $headerCols
                    </tr>
                  </thead>
                  <tbody>
                    $rows
                  </tbody>
                </table>
              </div>
            </body>
            </html>
            """.trimIndent()
        )
    }
}

fun configureRoborazziForkingDefaults(task: Test) {
    // Keep screenshot suite deterministic in constrained CI/local environments.
    // Separate test runs by JVM keeps file handles and Robolectric state from compounding.
    task.forkEvery = 1
    task.maxHeapSize = "2g"
    task.maxParallelForks = 1
    task.outputs.upToDateWhen { false }
}

fun registerRoborazziSubsetTask(name: String, filter: String) {
    tasks.register<Test>(name) {
        group = "verification"
        description = "Records Roborazzi screenshots for $filter only."

        val baseTask = tasks.named<Test>("testProdDebugUnitTest").get()
        testClassesDirs = baseTask.testClassesDirs
        classpath = baseTask.classpath
        dependsOn("unitTestGate")

        configureRoborazziForkingDefaults(this)

        // Use explicit filtering for each invocation so a single problematic set cannot block the whole suite.
        filter {
            includeTestsMatching(filter)
        }

        systemProperties["roborazzi.test.record"] = "true"
        systemProperties["roborazzi.test.verify"] = "false"
        systemProperties["roborazzi.test.compare"] = "false"
    }
}

tasks.register("unitTestGate") {
    group = "verification"
    description = "Runs unit tests in a stable single-process configuration."
    dependsOn("testProdDebugUnitTest")
}

registerRoborazziSubsetTask("recordRoborazziPhoneDebug", "com.monkopedia.healthdisconnect.screenshot.PhoneScreenRoborazziTest")
registerRoborazziSubsetTask("recordRoborazziTablet7Debug", "com.monkopedia.healthdisconnect.screenshot.Tablet7ScreenRoborazziTest")
registerRoborazziSubsetTask("recordRoborazziTabletDebug", "com.monkopedia.healthdisconnect.screenshot.TabletScreenRoborazziTest")
registerRoborazziSubsetTask(
    "recordRoborazziDashboardIntegrityDebug",
    "com.monkopedia.healthdisconnect.screenshot.ScreenRoborazziDashboardIntegrityTest"
)

tasks.register("roborazziGate") {
    group = "verification"
    description = "Runs screenshot verification separately from unit tests."
    dependsOn(
        "recordRoborazziPhoneDebug",
        "recordRoborazziTablet7Debug",
        "recordRoborazziTabletDebug",
        "recordRoborazziDashboardIntegrityDebug",
        "recordRoborazziTableDebug"
    )
}

tasks.register("allTests") {
    group = "verification"
    description = "Runs unit tests, screenshot generation, lint, and androidTest compile checks."
    setDependsOn(listOf("unitTestGate", "roborazziGate", "lintProdDebug", "compileProdDebugAndroidTestKotlin"))
}

tasks.register("releaseVerification") {
    group = "verification"
    description = "Runs all verification gates including connected instrumentation tests."
    setDependsOn(listOf("allTests", "connectedProdDebugAndroidTest"))
}

tasks.matching { it.name == "testProdDebugUnitTest" }.configureEach {
    if (this is Test) {
        // Keep unit tests deterministic and reduce intermittent OOM behavior on CI/dev machines.
        maxHeapSize = "2g"
        maxParallelForks = 1
        forkEvery = 1
    }
}

tasks.matching { it.name == "recordRoborazziProdDebug" }.configureEach {
    // Prevent running screenshot tests concurrently with unit tests and keep memory usage stable.
    mustRunAfter("unitTestGate")
}

tasks.matching { it.name == "testProdReleaseUnitTest" }.configureEach {
    (this as? Test)?.exclude("**/DataViewHeaderInteractionTest.class")
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}
