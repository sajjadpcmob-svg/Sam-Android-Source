-keep class androidx.room.** { *; }
-keep class ir.elviran.sam.data.** { *; }
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# JNI: the native library binds to these exact class/method names; R8 must not rename or strip them.
-keep class ir.elviran.sam.ai.local.LlamaNative { *; }
-keepclasseswithmembernames class * { native <methods>; }
