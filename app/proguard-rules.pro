# Trapix ProGuard Rules

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {**[] $VALUES; public *;}

# CameraX
-keep class androidx.camera.** { *; }

# Keep data models
-keep class com.trapix.app.data.model.** { *; }
