package com.example.note2snap.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.note2snap.model.Folder
import com.example.note2snap.model.Note
import com.example.note2snap.model.ScanHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    // --- FOLDERS ---
    @Query("SELECT * FROM folders ORDER BY id DESC")
    fun getAllFolders(): Flow<List<Folder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: Folder)

    @Delete
    suspend fun deleteFolder(folder: Folder)

    // --- NOTES / PHOTOS ---
    @Query("SELECT * FROM notes ORDER BY id DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId ORDER BY id DESC")
    fun getNotesByFolder(folderId: Int): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)

    // Delete single note by title (e.g., removing "OOP Discussion")
    @Query("DELETE FROM notes WHERE title = :title")
    suspend fun deleteNoteByTitle(title: String)

    // --- SCAN HISTORY ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScanHistory(history: ScanHistory)

    @Query("SELECT * FROM scan_history ORDER BY timestamp DESC")
    fun getAllScanHistory(): LiveData<List<ScanHistory>>

    @Query("DELETE FROM scan_history")
    suspend fun clearHistory()

    @Query("SELECT * FROM notes WHERE title = :title LIMIT 1")
    suspend fun getNoteByTitle(title: String): Note?

    @Delete
    suspend fun deleteScanHistory(history: ScanHistory)

    @Update
    suspend fun updateScanHistory(history: ScanHistory)

    @Update
    suspend fun updateNote(note: Note)

}