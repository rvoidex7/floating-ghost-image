# ProGuard rules for Floating Ghost Image
# Aggressive optimization for minimal APK size

# Optimization settings
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Remove debug logging
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Keep application classes
-keep public class com.rvoidex7.floatingghostimage.MainActivity
-keep public class com.rvoidex7.floatingghostimage.FloatingImageService

# Keep Android components
-keepclassmembers class * extends android.app.Activity {
   public void *(android.view.View);
}
-keepclassmembers class * extends android.app.Service {
   public void *(android.content.Intent);
}

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}

# Remove line numbers for smaller APK (comment out if you need stack traces)
# -keepattributes SourceFile,LineNumberTable

# Remove unused resources
-dontwarn org.xmlpull.v1.**
-dontwarn okhttp3.**
-dontwarn okio.**