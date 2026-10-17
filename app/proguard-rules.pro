# Keep the native bridge and the data classes the renderer relies on.
-keep class com.jabs.lumen.engine.RawDecoder { *; }
-keepclassmembers class com.jabs.lumen.model.** { *; }

# RuntimeShader is created by name at runtime.
-keepclassmembers class * {
    native <methods>;
}

# TFLite reaches into its JNI bridge reflectively.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**