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

# Keep Firebase
-keep class com.google.firebase.** { *; }
