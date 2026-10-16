# JGit and Apache MINA sshd resolve implementations through ServiceLoader and
# reflection, so R8 cannot see the wiring and would strip it.
-keep class org.eclipse.jgit.** { *; }
-keep class org.apache.sshd.** { *; }
-keepclassmembers class * implements org.eclipse.jgit.transport.SshSessionFactory { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.apache.sshd.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**

# sshd finds the Ed25519 implementation by the *string*
# "net.i2p.crypto.eddsa.EdDSASecurityProvider". R8 cannot see that reference;
# renaming the package makes sshd report Ed25519 unsupported and the transport
# says "publickey: no keys to try" on a perfectly good key. Release-only.
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# BouncyCastle registers algorithms by class name through its provider tables.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
