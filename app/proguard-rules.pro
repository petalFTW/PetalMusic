# Keep librespot (reflection: AudioOutput instantiation, protobuf, gson models)
-keep class xyz.gianlu.librespot.** { *; }
-dontwarn xyz.gianlu.librespot.**
# librespot's shaded SLF4J 1.x binding is reflectively loaded by name at
# session creation (Class.forName("org.slf4j.impl.StaticLoggerBinder")) —
# R8 renamed it and stripped its supertypes, crashing only release builds.
-keep class org.slf4j.** { *; }
-keep class ch.qos.logback.** { *; }
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
# librespot builds the audio sink by name through its no-arg constructor. R8
# can't see that call, stripped the constructor and broke Spotify sign-in.
-keep class org.rab1d.petal.music.sources.spotify.PetalSink { <init>(); }
# Full protobuf runtime finds generated accessors by name (getCanBePlayer...),
# so librespot's Spotify messages can't be renamed or stripped.
-keep class com.spotify.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessage { *; }
-keep class * extends com.google.protobuf.GeneratedMessage$Builder { *; }
-keep class * extends com.google.protobuf.GeneratedMessageV3 { *; }
-keep class * extends com.google.protobuf.GeneratedMessageV3$Builder { *; }
-keep class * implements com.google.protobuf.ProtocolMessageEnum { *; }
# NewPipeExtractor (reflection-based JS evaluation via Rhino)
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class org.mozilla.classfile.** { *; }
# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.rab1d.petal.music.** {
    *** Companion;
}
-keepclasseswithmembers class org.rab1d.petal.music.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# ONNX Runtime (JNI native method binding + reflection into native session)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
# Compile-only references from excluded logging backends and kotlinx FastServiceLoader
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.spi.SLF4JServiceProvider
-dontwarn org.slf4j.impl.**
-dontwarn kotlinx.coroutines.internal.**
-dontwarn com.sun.jna.**
-dontwarn javax.naming.**
