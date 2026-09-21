package com.example.note2snap

import com.example.note2snap.model.Folder

object FolderRepository {
    val foldersList = mutableListOf(
        Folder(id = 1, name = "CS101", dateCreated = "#AFC4F6"),
        Folder(id = 2, name = "CS202", dateCreated = "#5A7F08"),
        Folder(id = 3, name = "ALG101", dateCreated = "#C7EAC2")
    )

    fun addFolder(name: String, colorHex: String = "#AFC4F6") {
        val newId = foldersList.size + 1
        foldersList.add(Folder(id = newId, name = name, dateCreated = colorHex))
    }

    fun deleteFolder(folder: Folder) {
        foldersList.remove(folder)
    }

    fun updateFolder(folderId: Int, newName: String) {
        val index = foldersList.indexOfFirst { it.id == folderId }
        if (index != -1) {
            val oldFolder = foldersList[index]
            foldersList[index] = oldFolder.copy(name = newName)
        }
    }
}