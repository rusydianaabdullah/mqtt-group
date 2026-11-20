# Netty, which the MQTT client is built on, reaches for a lot of optional transports and codecs
# that an Android app never has: epoll and kqueue natives, the JCTools queues it substitutes at
# runtime, and its own logging bridges. None of it is on this classpath, and R8 should not
# refuse the build over it.
-dontwarn io.netty.**
-dontwarn org.jctools.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn reactor.blockhound.**

# Netty (via sun.misc.Unsafe field offsets for its lock-free queues, atomic field updaters, and
# method lookups for its buffer leak-tracking wrappers) resolves class members by string name at
# runtime in more places than any one package accounts for — io.netty.util.internal for
# `toLeakAwareBuffer` and friends, io.netty.buffer for the leak-aware wrappers themselves, and
# JCTools (org.jctools, which Netty's queues delegate to) for fields like `consumerIndex` and
# `producerIndex`. Each one R8 renames independently produces its own crash on a device that
# actually shrinks (release builds only — an emulator install of a debug build never hits this),
# so keep both libraries whole rather than patching one field at a time.
-keep class io.netty.** { *; }
-keep class org.jctools.** { *; }

# The MQTT client's message and exception types are constructed reflectively by its own
# builders, and the ones it throws end up in messages we show the user.
-keep class com.hivemq.client.** { *; }
-dontwarn com.hivemq.client.**

# SQLCipher calls into JNI both ways: the native library looks these classes up by name.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
