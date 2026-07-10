import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

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
        versionCode = 10
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // AGP 8.1+ embeds a "Dependency metadata" block in the APK signing scheme by
    // default. F-Droid's check rejects APKs containing it, so drop it from both
    // APK and AAB outputs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
            vcsInfo.include = false
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
            excludes += "/META-INF/{LICENSE,LICENSE.md,LICENSE-notice.md,NOTICE,NOTICE.md,LICENSE.txt,NOTICE.txt}"
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
    implementation(libs.androidx.material.icons.core)
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
    androidTestImplementation(libs.mockk.android)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.register("recordRoborazziTableDebug") {
    group = "verification"
    description =
        "Records Roborazzi screenshots and rewrites the dashboard as a side-by-side table by screen name."
    dependsOn(
        "recordRoborazziPhoneDebug",
        "recordRoborazziSmallPhoneDebug",
        "recordRoborazziTablet7Debug",
        "recordRoborazziTabletDebug",
        "recordRoborazziDashboardIntegrityDebug"
    )

    doLast {
        val screensDir = layout.projectDirectory.dir("src/test/screenshots").asFile
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
                    val src = "../../../src/test/screenshots/$fileName"
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

// mode is "record" (regenerate committed baselines) or "verify" (compare renders against the
// committed baselines and fail on an unexpected pixel change). Verify is the CI regression guard;
// record is the developer workflow for intentionally updating baselines.
fun registerRoborazziSubsetTask(name: String, filter: String, mode: String) {
    tasks.register<Test>(name) {
        group = "verification"
        description = "${mode.replaceFirstChar { it.uppercase() }}s Roborazzi screenshots for $filter only."

        val baseTask = tasks.named<Test>("testProdDebugUnitTest").get()
        testClassesDirs = baseTask.testClassesDirs
        classpath = baseTask.classpath
        dependsOn("unitTestGate")

        configureRoborazziForkingDefaults(this)

        // Use explicit filtering for each invocation so a single problematic set cannot block the whole suite.
        filter {
            includeTestsMatching(filter)
            // Capturing an open Compose DropdownMenu drives Roborazzi's multiple-windows path
            // (ShadowLooper.idle over the popup's enter animation), which spins for ~21 min per
            // bucket under Robolectric — it never surfaced before because screenshots ran
            // record-only-as-no-op. Excluded from the record/verify gate so it can't wedge the
            // 4-bucket suite; the popup-capture perf issue is tracked separately. See issue #39.
            excludeTestsMatching("*settingsThemeDropdownExpandedScreen")
        }

        val record = mode == "record"
        systemProperties["roborazzi.test.record"] = record.toString()
        systemProperties["roborazzi.test.verify"] = (!record).toString()
        systemProperties["roborazzi.test.compare"] = "false"
    }
}

tasks.register("unitTestGate") {
    group = "verification"
    description = "Runs unit tests in a stable single-process configuration."
    dependsOn("testProdDebugUnitTest")
}

// Each screenshot subset gets both a record task (regenerate committed baselines) and a verify
// task (compare against them). The dashboard-integrity check is a plain assertion test, so it only
// needs to run once; it rides along with the record tasks.
val roborazziSubsets = mapOf(
    "Phone" to "com.monkopedia.healthdisconnect.screenshot.PhoneScreenRoborazziTest",
    "SmallPhone" to "com.monkopedia.healthdisconnect.screenshot.SmallPhoneScreenRoborazziTest",
    "Tablet7" to "com.monkopedia.healthdisconnect.screenshot.Tablet7ScreenRoborazziTest",
    "Tablet" to "com.monkopedia.healthdisconnect.screenshot.TabletScreenRoborazziTest"
)
roborazziSubsets.forEach { (suffix, filter) ->
    registerRoborazziSubsetTask("recordRoborazzi${suffix}Debug", filter, "record")
    registerRoborazziSubsetTask("verifyRoborazzi${suffix}Debug", filter, "verify")
}
registerRoborazziSubsetTask(
    "recordRoborazziDashboardIntegrityDebug",
    "com.monkopedia.healthdisconnect.screenshot.ScreenRoborazziDashboardIntegrityTest",
    "record"
)

tasks.register("roborazziGate") {
    group = "verification"
    description = "Records screenshots + rebuilds the review dashboard (developer baseline update)."
    dependsOn(
        "recordRoborazziPhoneDebug",
        "recordRoborazziSmallPhoneDebug",
        "recordRoborazziTablet7Debug",
        "recordRoborazziTabletDebug",
        "recordRoborazziDashboardIntegrityDebug",
        "recordRoborazziTableDebug"
    )
}

tasks.register("verifyRoborazziGate") {
    group = "verification"
    description = "Verifies rendered screenshots against the committed baselines (CI regression guard)."
    dependsOn(
        "verifyRoborazziPhoneDebug",
        "verifyRoborazziSmallPhoneDebug",
        "verifyRoborazziTablet7Debug",
        "verifyRoborazziTabletDebug"
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
        // Fail fast instead of wedging CI for hours if a test hangs.
        timeout.set(Duration.ofMinutes(10))
        // Identify the intermittent CI hang (issue #18). The task-level timeout above
        // hard-kills the worker without writing an HTML report, so a hang otherwise leaves
        // no trace of which test stalled. Logging start/finish events keeps that trace in
        // the (always-captured) CI console: with the sequential forks above, the last test
        // logged "STARTED" without a matching result is the one that hung.
        testLogging {
            events("started", "passed", "skipped", "failed")
        }
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

// Disable baseline profile ArtProfile tasks. Compose pulls in profileinstaller
// transitively, which adds non-deterministic baseline.prof generation that
// breaks F-Droid reproducible builds.
tasks.matching { it.name.contains("ArtProfile") }.configureEach {
    enabled = false
}
