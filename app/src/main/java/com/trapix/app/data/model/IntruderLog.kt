package com.trapix.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "intruder_logs")
data class IntruderLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val imagePath: String,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val locationAddress: String = "",
    val attemptNumber: Int = 1,
    val cameraUsed: String = "front", // "front" or "rear"
    val deviceInfo: String = "",
    val isSavedToGallery: Boolean = false
)
