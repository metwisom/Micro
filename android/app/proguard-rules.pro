# Keep readable crash stack traces from release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI: the C side binds methods by their exact Java names (Java_ru_metwisom_micro_OpusNative_*),
# so the class and its native methods must not be renamed or stripped.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class ru.metwisom.micro.OpusNative { *; }

# BouncyCastle: only the lightweight API is referenced, directly and without reflection
# (CryptoSession / Identity), so R8 keeps exactly the engines we call and drops the JCE provider,
# TLS, PQC, OpenPGP and ASN.1 subsystems that make up the bulk of the jar. Silence its optional deps.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
