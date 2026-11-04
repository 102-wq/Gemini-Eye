# ==============================================================================
# ProGuard / R8 Rules for BardQuotaHook (LibXposed API 102 + Legacy Xposed 82)
# Ensures Xposed entry points, inner hook callbacks/lambdas, Android components,
# and reflection-based Cronet/gRPC/ViewModel lookups survive release builds.
# ==============================================================================

# 1. Preserve Java/Kotlin reflection metadata, inner-class links & stack traces
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable,MethodParameters
-keep class kotlin.Metadata { *; }

# Keep Xposed text entry-point descriptors aligned with un-obfuscated class names
-adaptresourcefilecontents META-INF/xposed/java_init.list,assets/xposed_init

# 2. Suppress R8 warnings for compileOnly Xposed APIs & host-reflected libraries
-dontwarn io.github.libxposed.**
-dontwarn io.github.libxposed.annotation.**
-dontwarn de.robv.android.xposed.**
-dontwarn org.chromium.net.**
-dontwarn io.grpc.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn dalvik.system.**
-dontwarn android.app.ActivityThread
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.lifecycle.**

# 3. Keep Modern LibXposed API 102 Entry Point & ALL Inner/Anonymous Hook Classes
# Strict -keep (no allowoptimization/allowshrinking/allowobfuscation) prevents R8
# from merging, inlining, or renaming XposedInterface.Hooker SAM callbacks.
-keep public class com.example.bardquotahook.ModernQuotaModule {
    public <init>();
    public <init>(...);
    *;
}
-keep class com.example.bardquotahook.ModernQuotaModule$* {
    *;
}
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public <init>(...);
    *;
}
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker {
    *;
}

# 4. Keep Legacy Xposed / LSPosed API 82 Entry Point & XC_MethodHook Callbacks
-keep public class com.example.bardquotahook.QuotaHookEntry {
    public <init>();
    *;
}
-keep class com.example.bardquotahook.QuotaHookEntry$* {
    *;
}
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public <init>();
    *;
}
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit {
    public <init>();
    *;
}
-keep class * extends de.robv.android.xposed.XC_MethodHook {
    *;
}

# 5. Keep Core In-Process Interception Engine, ProtoBuf Parser & Inner Bridges
-keep public class com.example.bardquotahook.QuotaCoreEngine {
    *;
}
-keep class com.example.bardquotahook.QuotaCoreEngine$* {
    *;
}
-keep public class com.example.bardquotahook.QuotaTextParser {
    *;
}
-keep class com.example.bardquotahook.QuotaTextParser$* {
    *;
}

# 6. Keep Capsule HUD Custom View, System Overlay Controller & Manifest Targets
# Referenced via AndroidManifest.xml and explicit Intent.setClassName(...) IPC.
-keep public class com.example.bardquotahook.QuotaHudView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    *;
}
-keep class com.example.bardquotahook.QuotaHudView$* {
    *;
}
-keep public class com.example.bardquotahook.OverlayHudController {
    *;
}
-keep class com.example.bardquotahook.OverlayHudController$* {
    *;
}
-keep public class com.example.bardquotahook.QuotaSyncProvider {
    public <init>();
    *;
}
-keep class com.example.bardquotahook.QuotaSyncProvider$* {
    *;
}
-keep public class com.example.bardquotahook.HookTelemetryReceiver {
    public <init>();
    *;
}
-keep public class com.example.bardquotahook.MainActivity {
    public <init>();
    *;
}
-keep class com.example.bardquotahook.MainActivity$* {
    *;
}

# 7. Package-wide safeguard for all remaining module classes, interfaces & enums
-keep class com.example.bardquotahook.** {
    *;
}
-keep interface com.example.bardquotahook.** {
    *;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
