# ── Crash-safety keeps (belt and braces on top of ML Kit's consumer rules) ──
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class org.json.** { *; }
-dontwarn com.google.mlkit.**
