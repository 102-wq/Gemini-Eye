-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep,allowoptimization public class com.example.bardquotahook.ModernQuotaModule {
    public <init>();
    *;
}
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    *;
}
-keep public class com.example.bardquotahook.QuotaHookEntry {
    public <init>();
    *;
}
-keep public class com.example.bardquotahook.MainActivity {
    public <init>();
    *;
}
-keep public class com.example.bardquotahook.HookTelemetryReceiver {
    public <init>();
    *;
}
-keep class com.example.bardquotahook.** {
    *;
}
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public <init>();
    *;
}
-keepattributes *Annotation*,InnerClasses,Signature
