# R8 optimisation is disabled, and this is load-bearing.
#
# MediaPipe's Graph class initialises a logger with Flogger's
# FluentLogger.forEnclosingClass(), which identifies the logging class by walking the
# call stack. R8's optimiser inlines and merges frames, so the walk finds no caller,
# forEnclosingClass() throws IllegalStateException, Graph's static initialiser dies, and
# every MediaPipe task fails with NoClassDefFoundError. Observed on a real device
# (Android 16, arm64) with shrinking and keep rules that were otherwise correct.
#
# Shrinking still runs — that is what keeps the APK at 49 MB rather than 99 MB — and
# tree-shaking alone does not rewrite call stacks.
-dontoptimize

# Flogger walks the stack to name its caller; never let it be renamed or reshaped.
-keep class com.google.common.flogger.** { *; }
-keepnames class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
-dontwarn sun.misc.**

# Obfuscation is disabled on purpose. This app ships to a phone the developer cannot
# attach a debugger to, and its in-app diagnostics report exists to explain failures by
# their stack traces. Renamed classes would make those reports useless. Shrinking still
# runs, which is where essentially all of the size saving comes from anyway.
-dontobfuscate

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
