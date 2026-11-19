# org.json and OkHttp are used reflectively-free; keep zxing capture activity
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
