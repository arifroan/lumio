# Keep the JavaScript bridge methods exposed to the local offline WebView.
-keepclassmembers class com.lumio.player.MainActivity$LumioBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class androidx.media3.** { *; }
