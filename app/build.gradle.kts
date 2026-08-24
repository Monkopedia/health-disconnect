import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

plugins {
    // AGP 9.0 provides built-in Kotlin support, so the standalone org.jetbrains.kotlin.android
    // plugin is no longer applied (https://kotl.in/gradle/agp-built-in-kotlin). The compose,
    // serialization, and kapt Kotlin plugins are still applied explicitly.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    // Room's annotation processor runs via KSP; kapt is incompatible with AGP 9 built-in Kotlin.
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.monkopedia.healthdisconnect"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.monkopedia.healthdisconnect"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.2.2"

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
    // Under AGP 9 built-in Kotlin the Kotlin DSL lives inside the android block.
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
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

// Turn off the Compose compiler's group-mapping file. Under AGP 9 this feature makes the build
// resolve org.jetbrains.kotlin:compose-group-mapping at AGP's OWN bundled Kotlin version — not
// the version this project pins in libs.versions.toml. Measured on AGP 9.2.1 by flipping this
// flag to true: :app:bundleProdRelease fails at :app:produceProdReleaseComposeMapping with
// "Could not find org.jetbrains.kotlin:compose-group-mapping:2.2.10". Maven Central's earliest
// published version of that artifact is 2.3.0-Beta1, so no 2.2.x coordinate resolves at all.
//
// DO NOT re-enable this on the reasoning that a Kotlin bump fixes it. The artifact IS published
// for our pinned Kotlin (2.3.21) — the resolution above simply does not use our version, so
// raising it changes nothing. The first attempt at a workaround disabled the ComposeMapping
// tasks wholesale, which also killed mergeProdReleaseComposeMapping and left
// packageProdReleaseBundle without the outputs/mapping/prodRelease/mapping.txt it consumes;
// that is what broke the release AAB in the v1.2.1 cycle (#60).
//
// The mapping only deobfuscates Compose group keys in stack traces (optional diagnostics), so
// disabling it is behavior-neutral and lets the plain R8 mapping flow feed both the APK and AAB.
composeCompiler {
    includeComposeMappingFile.set(false)
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
    ksp(libs.androidx.room.compiler)
    // DataViewRoomTransactionTest defines a legacy @Database in the unit-test source set; KSP
    // needs the Room processor wired per source set (kapt processed it implicitly).
    kspTest(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)
    // Pulls the unit-test classpath up to the espresso-core 3.7.0 that androidTest already
    // declares. NOT a new dependency -- ui-test-junit4 drags espresso-core in transitively and was
    // resolving 3.5.1 here; this is the version alignment, nothing more.
    //
    // Honest status at the CURRENT targetSdk (36): this line is NOT load-bearing. Measured on this
    // branch -- remove it, and prodDebugUnitTestRuntimeClasspath falls back to espresso-core 3.5.1
    // and all 352 unit tests still pass. Do not read a live breakage into it.
    //
    // Why keep it anyway. Compose's Robolectric idling strategy routes through Espresso.onIdle,
    // which builds Espresso's InputManagerEventInjectionStrategy; that reflects for
    // android.hardware.input.InputManager#getInstance(), a hidden static the platform REMOVED in
    // API 37 (present in android-all-instrumented-16, absent in -17). Robolectric picks its
    // android-all jar from targetSdk, so at 36 the method is still there and eager 3.5.1 resolves
    // it fine; at targetSdk 37 it is gone and 18 Compose UI tests die at Espresso.onIdle. 3.7.0
    // wraps the lookup in a lazy ReflectiveMethod and tolerates its absence. Keeping the alignment
    // costs nothing, matches androidTest, and is a precondition for the targetSdk 37 work
    // (see #91) rather than a fix for anything currently broken.
    testImplementation(libs.androidx.espresso.core)
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
    // Pin the render JVM to UTC so screenshots do not depend on the HOST's timezone.
    //
    // The screenshot clock is pinned to a fixed instant, but it was rendered through
    // ZoneId.systemDefault() — and so is every production formatting path the screens exercise
    // (ChartGeometry axis labels, SettingsScreen date ranges, the "Last refreshed" header).
    // A fixed instant plus a floating zone is not a fixed wall-clock time: CI runs UTC and
    // renders "9:30:00 AM" while a developer in America/New_York renders "4:30:00 AM" from the
    // same Instant. That made 72 of 160 committed baselines differ on a developer machine
    // before a single line of app code changed, which is why the verify gate was unrunnable
    // locally and ended up disabled (see #64, #73).
    //
    // UTC specifically, because CI already runs UTC — so this converges local onto the
    // committed baselines rather than rewriting them. Production is untouched: real users
    // still get ZoneId.systemDefault(), which is correct for them.
    task.systemProperty("user.timezone", "UTC")
    task.environment("TZ", "UTC")
}

// mode is "record" (regenerate committed baselines) or "verify" (compare renders against the
// committed baselines and fail on an unexpected pixel change).
//
// Record is what actually runs. CI runs recordRoborazziTableDebug and auto-commits any changed
// baselines back onto the PR branch (.github/workflows/ci.yml:99-113), so the reviewer reads the
// PNG diff in the PR — that is the review, and it is the owner's standing decision (#64). The
// verify tasks are registered but nothing in CI, allTests, or roborazziGate depends on them: they
// exist as a LOCAL diagnostic, the only way to make the suite compare instead of record. Keep
// them; #73 and #91 use them. Nothing here gates a build on pixel equality.
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

// Local diagnostic, not a gate: no CI job and no other task depends on this. Run it by hand to
// make the screenshot suite compare against the committed baselines instead of rewriting them.
tasks.register("verifyRoborazziGate") {
    group = "verification"
    description =
        "Verifies rendered screenshots against the committed baselines (local diagnostic, not run by CI)."
    dependsOn(
        "verifyRoborazziPhoneDebug",
        "verifyRoborazziSmallPhoneDebug",
        "verifyRoborazziTablet7Debug",
        "verifyRoborazziTabletDebug"
    )
}

tasks.register("allTests") {
    group = "verification"
    description =
        "Runs unit tests, screenshot generation, lint, and androidTest + demo-flavor compile checks."
    // compileDemoDebugSources: the demo flavor is ~700 lines that nothing else builds. It does not
    // ship (release.yml builds prod only, and it carries its own applicationIdSuffix), but it IS the
    // flavor used for on-device rendering checks — including the A/B that verified the v1.2.2
    // LegacyFqnRecovery fix. DemoDataSeeder calls encodeDataViewEntity, so a change to the
    // persistence codec can break it with nothing to catch that. Guarding it keeps the instrument
    // working; see issue #65.
    setDependsOn(
        listOf(
            "unitTestGate",
            "roborazziGate",
            "lintProdDebug",
            "compileProdDebugAndroidTestKotlin",
            "compileDemoDebugSources"
        )
    )
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

// Ordering only, and currently unexercised: recordRoborazziProdDebug is the Roborazzi plugin's own
// per-variant task, and no documented workflow runs it — roborazziGate and allTests drive the
// recordRoborazzi*Debug subset tasks registered above instead. mustRunAfter only applies when both
// tasks are in the same invocation, so this constrains nothing today. Left in place because it is
// the right ordering if that task is ever run alongside unitTestGate.
tasks.matching { it.name == "recordRoborazziProdDebug" }.configureEach {
    // Prevent running screenshot tests concurrently with unit tests and keep memory usage stable.
    mustRunAfter("unitTestGate")
}

// This exclusion has never applied: there is no testProdReleaseUnitTest task. `:app:tasks --all`
// registers only testProdDebugUnitTest and testDemoDebugUnitTest — unit tests run on debug
// variants only — so tasks.matching finds nothing here and silently succeeds. Kept rather than
// deleted so the intent survives if release unit tests are ever enabled; do not read it as an
// active exclusion of DataViewHeaderInteractionTest, which runs in full under
// testProdDebugUnitTest.
tasks.matching { it.name == "testProdReleaseUnitTest" }.configureEach {
    (this as? Test)?.exclude("**/DataViewHeaderInteractionTest.class")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Disable baseline profile ArtProfile tasks. Compose pulls in profileinstaller
// transitively, which adds non-deterministic baseline.prof generation that
// breaks F-Droid reproducible builds.
tasks.matching { it.name.contains("ArtProfile") }.configureEach {
    enabled = false
}

// REQUIRED BY ROBOLECTRIC 4.17 AT EVERY targetSdk — DO NOT DELETE.
//
// This has nothing to do with the SDK level. It was first hit while targetSdk was 37, but it was
// measured directly at targetSdk 36 as well: deleting these two lines on Robolectric 4.17-beta-3
// with targetSdk = 36 fails 307 of the 352 unit tests -- every Robolectric-backed test -- with
//   java.lang.RuntimeException: Failed to interact with raw FileDescriptor internals;
//                               perhaps JRE has changed?
//     at AndroidInterceptors$FileDescriptorInterceptor.setInt(AndroidInterceptors.java:88)
//   Caused by: java.lang.IllegalAccessException: ... cannot access class
//     jdk.internal.access.SharedSecrets (in module java.base) because module java.base does not
//     export jdk.internal.access to unnamed module
//
// Cause: 4.17's AndroidTestEnvironment boots through
// com.android.internal.os.ApplicationSharedMemory (referenced 3x in 4.17-beta-3, 0x in 4.16.1),
// whose create() path drives Robolectric's FileDescriptorInterceptor, which reflects into
// jdk.internal.access.SharedSecrets — a package java.base does not export to the unnamed module
// under JDK 21's default module policy. Opening the package is the documented fix. It is a JVM
// module-access requirement of Robolectric 4.17 itself, so it stands or falls with the
// Robolectric version, never with compileSdk/targetSdk.
//
// Applied to every Test task because the four screenshot subsets are separate Test tasks and
// inherit nothing from the base one.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}
