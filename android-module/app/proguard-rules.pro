-keep public class com.example.bardquotahook.QuotaHookEntry {
    public <init>();
    *;
}
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public <init>();
    *;
}
-keepattributes *Annotation*,InnerClasses,Signature
