# R8 Full-Mode Obfuscation, Security & Optimization Rules for Pixel AR Touchpad
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Strip all android.util.Log calls in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# Strip Kotlin Intrinsics null-check parameter string overhead in release builds
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
    public static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
}

# Keep only the required framework constructors for AndroidManifest entry points
# so internal methods and fields can be aggressively inlined and obfuscated by R8
-keep public class com.example.MainActivity {
    public <init>();
}
-keep public class com.example.TouchpadAccessibilityService {
    public <init>();
}
-keep class com.example.DeXCrosshairTrackpadView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
