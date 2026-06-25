# crm-callmonitor ProGuard rules

# Keep EncryptedSharedPreferences classes
-keep class androidx.security.crypto.** { *; }

# Keep ZXing classes (used via zxing-android-embedded)
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# Keep our own classes (no reflection used, but safe)
-keep class com.xiyutianhe.crmcallmonitor.** { *; }

# Do not strip Log calls in debug builds (already conditional via BuildConfig)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
