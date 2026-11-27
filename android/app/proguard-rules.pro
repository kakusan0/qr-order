# Keep ML Kit / Firebase ComponentRegistrar no-arg constructors for ComponentDiscovery
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
-keep class com.google.mlkit.**Registrar {
    public <init>();
}
