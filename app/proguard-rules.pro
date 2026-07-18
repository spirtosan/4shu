# 4shu — keep all app classes from being stripped or renamed
-keep class com.fshu.** { *; }

# Keep WebSocket and networking
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep Gson serialization
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Trail wire models (SPEC_T13.md §2.1) are (de)serialized via plain reflective
# Gson field mapping (TrailPointCodec), unlike the rest of the app's manual
# JsonObject walking — the blanket com.fshu.** keep above still lets R8 strip
# their backing fields, which silently breaks the wire shape. Keep fields only;
# no need to block renaming of methods/classes already covered above.
-keepclassmembers class com.fshu.next.trail.TrailPointData { <fields>; }
-keepclassmembers class com.fshu.next.trail.CellInfo { <fields>; }
-keepclassmembers class com.fshu.next.trail.WifiAp { <fields>; }
-keepclassmembers class com.fshu.next.trail.WifiInfo { <fields>; }
-keepclassmembers class com.fshu.next.trail.LastFix { <fields>; }

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
