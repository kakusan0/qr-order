# Keep ML Kit / Firebase ComponentRegistrar no-arg constructors for ComponentDiscovery
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
-keep class com.google.mlkit.**Registrar {
    public <init>();
}

# Keep WebView JavaScriptInterface bridge for LocationMapPickerDialog
-keepclassmembers class com.example.myapplication.ui.components.LocationMapJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
