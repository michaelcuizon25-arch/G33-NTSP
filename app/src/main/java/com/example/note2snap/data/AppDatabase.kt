package com.example.note2snap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.note2snap.model.Folder
import com.example.note2snap.model.Note
import com.example.note2snap.model.ScanHistory

@Database(
    entities = [Folder::class, Note::class, ScanHistory::class], // <-- Added ScanHistory
    version = 3, // <-- Bumped version to 3 for schema update
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "note2snap_database"
                )
                    .fallbackToDestructiveMigration() // Rebuilds database cleanly when structural changes occur
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}