package com.example.note2snap.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val dateCreated: String,
    val timestamp: Long = System.currentTimeMillis() // Required for Time sorting
)