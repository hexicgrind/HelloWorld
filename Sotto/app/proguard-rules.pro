# --- TensorFlow Lite ---------------------------------------------------------
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# --- MediaPipe ---------------------------------------------------------------
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**

# --- kotlinx.serialization ---------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ai.sotto.assistant.**$$serializer { *; }
-keepclassmembers class ai.sotto.assistant.** { *** Companion; }
-keepclasseswithmembers class ai.sotto.assistant.** { kotlinx.serialization.KSerializer serializer(...); }

# --- OkHttp ------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
