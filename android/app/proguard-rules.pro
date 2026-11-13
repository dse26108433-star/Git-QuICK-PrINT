# kotlinx.serialization: keep the generated serializers of our data classes
-keepattributes InnerClasses, Signature
-keepclassmembers class edu.campus.printapp.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class edu.campus.printapp.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
