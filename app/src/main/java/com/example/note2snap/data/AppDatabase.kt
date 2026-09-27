package com.example.note2snap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.note2snap.model.Folder
import com.example.note2snap.model.Note
import com.example.note2snap.model.ScanHistory

@Database(
    entities = [
        Folder::class,
        Note::class,
        ScanHistory::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appDao(): AppDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(
                    database: SupportSQLiteDatabase
                ) {
                    database.execSQL(
                        """
                        ALTER TABLE folders
                        ADD COLUMN colorHex TEXT
                        NOT NULL DEFAULT '#AFC4F6'
                        """.trimIndent()
                    )
                }
            }

        fun getDatabase(
            context: Context
        ): AppDatabase {

            return INSTANCE ?: synchronized(this) {

                val instance =
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "note2snap_database"
                    )
                        .addMigrations(
                            MIGRATION_3_4
                        )
                        .fallbackToDestructiveMigration()
                        .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
