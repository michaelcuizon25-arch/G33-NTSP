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

    // Synchronous list fetch for popup/dialog picker choices
    @Query("SELECT * FROM folders ORDER BY name ASC")
    suspend fun getAllFoldersList(): List<Folder>

    @Query("SELECT * FROM folders WHERE id = :folderId")
    suspend fun getFolderById(folderId: Int): Folder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: Folder)

    @Delete
    suspend fun deleteFolder(folder: Folder)

    // --- NOTES / PHOTOS ---
    @Query("SELECT * FROM notes ORDER BY id DESC")
    fun getAllNotes(): Flow<List<Note>>

    // Notes assigned to a specific folder
    @Query("SELECT * FROM notes WHERE folderId = :folderId ORDER BY id DESC")
    fun getNotesByFolder(folderId: Int): Flow<List<Note>>

    // Notes NOT assigned to any folder (Main Screen / Unassigned)
    @Query("SELECT * FROM notes WHERE folderId IS NULL ORDER BY id DESC")
    fun getUnassignedNotes(): Flow<List<Note>>

    // DIRECT TRANSFER QUERY: Update folder assignment without rewriting the entire Note object
    @Query("UPDATE notes SET folderId = :folderId WHERE id = :noteId")
    suspend fun updateNoteFolder(noteId: Int, folderId: Int?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note)

    @Update
    suspend fun updateNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: Int): Note?

    @Query("SELECT * FROM notes WHERE title = :title LIMIT 1")
    suspend fun getNoteByTitle(title: String): Note?

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

    @Delete
    suspend fun deleteScanHistory(history: ScanHistory)

    @Update
    suspend fun updateScanHistory(history: ScanHistory)

    @Query("UPDATE notes SET folderId = :folderId WHERE id IN (:noteIds)")
    suspend fun moveNotesToFolder(noteIds: List<Int>, folderId: Int)
}