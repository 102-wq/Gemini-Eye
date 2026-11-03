-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**
-dontwarn io.github.libxposed.annotation.**

-keep public class com.example.bardquotahook.QuotaHookEntry {
    public <init>();
    *;
}
-keep public class com.example.bardquotahook.MainActivity {
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
