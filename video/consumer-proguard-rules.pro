# Kontext Video SDK consumer ProGuard rules.
#
# Packaged in the AAR; auto-applied by R8 in the consuming app's release
# build. Keeps the public API and the serializers kotlinx.serialization
# generates for the wire models.

-keep public class so.kontext.video.** { public *; }

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class so.kontext.video.** {
    *** Companion;
}
-keepclasseswithmembers class so.kontext.video.** {
    kotlinx.serialization.KSerializer serializer(...);
}
