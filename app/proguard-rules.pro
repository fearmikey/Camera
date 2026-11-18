# JNI symbols (Java_com_fearmikey_camera_..._nativeXxx) are resolved by name from
# libpanorama_engine.so, so the bridge classes and their native methods must not be
# renamed or removed. The native code does not call back into Java via reflection/FindClass.
-keep class com.fearmikey.camera.data.engine.processing.NativePanoramaBridge { *; }
-keep class com.fearmikey.camera.data.engine.processing.NativeVisionBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
