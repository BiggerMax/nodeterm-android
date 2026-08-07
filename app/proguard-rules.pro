# The tweetnacl port uses reflective-ish field access in places; keep it whole.
-keep class com.iwebpp.crypto.** { *; }

# sshj (LAN/SSH transport): reflective key/algorithm plumbing; keep it whole.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }

# EdDSA provider (ed25519 keys) — registered reflectively via Security.addProvider.
-keep class net.i2p.crypto.eddsa.** { *; }

# Bouncy Castle provider classes are looked up by algorithm name.
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.** { *; }

# kotlinx.serialization: keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the FCM service names.
-keep class com.nodeterm.android.notify.NodetermMessagingService { *; }
