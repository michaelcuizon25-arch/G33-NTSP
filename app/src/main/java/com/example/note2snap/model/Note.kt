package com.example.note2snap.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = Folder::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["folderId"])]
)
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val folderId: Int? = null, // Null if the note doesn't belong to any folder
    val title: String,
    val content: String = "",  // Stores parsed Rule Engine content
    val imagePath: String,     // Path to physical photo stored on phone
    val dateEdited: String,
    val isStarred: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(), // Required for Time sorting
    val fileSizeBytes: Long = 0L                      // Required for Size sorting
) {
    // Helper property to dynamically extract file type extension (e.g. "jpg", "pdf")
    val fileType: String
        get() = imagePath.substringAfterLast('.', "jpg")
}