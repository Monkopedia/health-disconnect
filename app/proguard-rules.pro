# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Health Connect record types (load-bearing under R8) ---
# The app persists and looks up record types by their qualified class name
# (RecordSelection.fqn = KClass.qualifiedName) and derives labels from simpleName.
# Without these keep rules R8 obfuscates the record classes, so qualifiedName /
# simpleName return unstable per-build names (e.g. "i5.y1"), which (a) breaks the
# fqn->class registry after any app update — the stored name no longer matches —
# and (b) surfaces obfuscated labels in the UI. Keep the record class NAMES stable.
-keepnames class androidx.health.connect.client.records.** { *; }