# 4shu — keep all app classes from being stripped or renamed
-keep class com.fshu.** { *; }

# Keep WebSocket and networking
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep Gson serialization
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep Room database classes
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep WebRTC
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }

# Keep Firebase
-keep class com.google.firebase.** { *; }

# Keep Bouncy Castle lightweight API (X25519 key generation and agreement)
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.** { *; }
