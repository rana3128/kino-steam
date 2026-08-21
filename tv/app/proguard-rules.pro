# kotlinx.serialization keeps its generated serializers via annotations.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.vpsin.kino.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.vpsin.kino.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
