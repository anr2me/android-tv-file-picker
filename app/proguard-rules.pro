# R8 rules for the release build.
#
# The manifest keeps all activities and the document provider.
# OkHttp and Okio add their own rules. The rules below are for the libraries
# for the network shares. These libraries load some classes by name.
# R8 cannot see these uses, so the rules keep the classes.

# Keep the information that reflection and annotations need.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ---- SMB: smbj and its helper libraries ----
# smbj (com.hierynomus.smbj and others), asn-one (com.hierynomus.asn1).
-keep class com.hierynomus.** { *; }
# mbassador: the event bus of smbj. It reads annotations and uses reflection.
-keep class net.engio.mbassy.** { *; }

# ---- BouncyCastle ----
# The provider loads the algorithm classes by name.
# If an algorithm is not found at run time, replace these rules with:
#   -keep class org.bouncycastle.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**

# ---- Classes that exist on a desktop JVM, but not on Android ----
# The libraries mention these classes in code paths that the app does not use.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.naming.**
-dontwarn sun.security.**
-dontwarn com.sun.security.**
-dontwarn java.lang.management.**
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn org.slf4j.**
