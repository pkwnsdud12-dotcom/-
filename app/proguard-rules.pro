# Camera2Interop / CameraX 리플렉션 보호
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# OpenGL native 바인딩 보호
-keep class android.opengl.** { *; }
