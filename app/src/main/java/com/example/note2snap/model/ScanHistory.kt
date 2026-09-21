package com.example.note2snap.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_history")
data class ScanHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val date: String,                  // e.g., "August 25, 2026 - 09:15 PM"
    val imagePath: String = "",        // Path for history item thumbnail
    val isSyncedLocal: Boolean = true,
    val timestamp: Long = System.currentTimeMillis() // Used for sorting newest first
)