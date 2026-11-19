# org.json and OkHttp are used reflectively-free; keep zxing capture activity
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# PDF export helper lives in android.print to reach package-private callbacks
-keep class android.print.PdfExporter { *; }
-keep class android.print.PdfExporter$* { *; }
# JavaScript bridges
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# PDFBox (attachments): optional JPEG2000 / Gemalto deps are not used
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.filter.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
