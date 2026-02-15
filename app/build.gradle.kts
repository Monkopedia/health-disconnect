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

    buildTypes {
        release {
            isMinifyEnabled = false
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
    dependsOn("recordRoborazziDebug")

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

tasks.register("allTests") {
    group = "verification"
    description = "Runs unit tests and screenshot generation used by this repository's local CI gate."
    dependsOn("testDebugUnitTest", "recordRoborazziDebug")
}

tasks.matching { it.name == "testReleaseUnitTest" }.configureEach {
    (this as? Test)?.exclude("**/DataViewHeaderInteractionTest.class")
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}
