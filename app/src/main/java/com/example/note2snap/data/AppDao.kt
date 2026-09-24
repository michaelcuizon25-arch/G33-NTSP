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

    @Query("SELECT * FROM folders ORDER BY name ASC")
    suspend fun getAllFoldersList(): List<Folder>

    @Query("SELECT * FROM folders WHERE id = :folderId")
    suspend fun getFolderById(folderId: Int): Folder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: Folder): Long

    @Delete
    suspend fun deleteFolder(folder: Folder)


    // --- NOTES / PHOTOS ---
    @Query("SELECT * FROM notes ORDER BY id DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId ORDER BY id DESC")
    fun getNotesByFolder(folderId: Int): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE folderId IS NULL ORDER BY id DESC")
    fun getUnassignedNotes(): Flow<List<Note>>

    @Query("UPDATE notes SET folderId = :folderId WHERE id = :noteId")
    suspend fun updateNoteFolder(noteId: Int, folderId: Int?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: Int): Note?

    @Query("SELECT * FROM notes WHERE title = :title LIMIT 1")
    suspend fun getNoteByTitle(title: String): Note?

    @Query("SELECT * FROM notes WHERE imagePath = :path LIMIT 1")
    suspend fun getNoteByPath(path: String): Note?

    // Sync Title in Notes table by image path
    @Query("UPDATE notes SET title = :newTitle WHERE imagePath = :path")
    suspend fun updateNoteTitleByPath(path: String, newTitle: String)

    // Sync Content in Notes table by image path
    @Query("UPDATE notes SET content = :newContent WHERE imagePath = :path")
    suspend fun updateNoteContentByPath(path: String, newContent: String)

    @Query("UPDATE notes SET title = :newTitle, imagePath = :newPath WHERE id = :noteId")
    suspend fun updateNoteTitleAndPath(noteId: Int, newTitle: String, newPath: String)

    @Query("DELETE FROM notes WHERE title = :title")
    suspend fun deleteNoteByTitle(title: String)

    @Query("DELETE FROM notes WHERE imagePath = :path")
    suspend fun deleteNoteByPath(path: String)

    @Query("UPDATE notes SET folderId = :folderId WHERE id IN (:noteIds)")
    suspend fun moveNotesToFolder(noteIds: List<Int>, folderId: Int)


    // --- SCAN HISTORY ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScanHistory(history: ScanHistory): Long

    @Query("SELECT * FROM scan_history ORDER BY timestamp DESC")
    fun getAllScanHistory(): LiveData<List<ScanHistory>>

    @Query("SELECT * FROM scan_history WHERE id = :historyId LIMIT 1")
    suspend fun getScanHistoryById(historyId: Int): ScanHistory?

    @Query("SELECT * FROM scan_history WHERE imagePath = :path LIMIT 1")
    suspend fun getScanHistoryByPath(path: String): ScanHistory?

    @Query("SELECT * FROM scan_history WHERE imagePath = :path LIMIT 1")
    suspend fun getScanHistoryByImagePath(path: String): ScanHistory?

    // Sync Title in History table by image path
    @Query("UPDATE scan_history SET title = :newTitle WHERE imagePath = :path")
    suspend fun updateScanHistoryTitleByPath(path: String, newTitle: String)

    @Query("UPDATE scan_history SET title = :newTitle, imagePath = :newPath WHERE id = :historyId")
    suspend fun updateScanHistoryTitleAndPath(historyId: Int, newTitle: String, newPath: String)

    @Delete
    suspend fun deleteScanHistory(history: ScanHistory)

    @Query("DELETE FROM scan_history WHERE imagePath = :path")
    suspend fun deleteScanHistoryByPath(path: String)

    @Update
    suspend fun updateScanHistory(history: ScanHistory)

    @Query("DELETE FROM scan_history")
    suspend fun clearHistory()
}