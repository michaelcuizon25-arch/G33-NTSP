package com.example.note2snap.activities

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.data.AppDatabase
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class StorageFragment : Fragment(R.layout.fragment_storage) {

    private lateinit var tvTotalStorage: TextView
    private lateinit var tvDatabaseStorage: TextView
    private lateinit var tvImagesStorage: TextView
    private lateinit var tvTempStorage: TextView
    private lateinit var tvOtherStorage: TextView

    private lateinit var tvNoteCount: TextView
    private lateinit var tvFolderCount: TextView
    private lateinit var tvImageCount: TextView
    private lateinit var tvHistoryCount: TextView

    private lateinit var btnClearTemporary: View
    private lateinit var tvClearTemporaryTitle: TextView
    private lateinit var tvClearTemporarySubtitle: TextView

    private var currentTempBytes: Long = 0L
    private var latestHistoryPaths: List<String> = emptyList()

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        setBottomNavigationVisible(false)

        tvTotalStorage =
            view.findViewById(
                R.id.tvTotalStorage
            )

        tvDatabaseStorage =
            view.findViewById(
                R.id.tvDatabaseStorage
            )

        tvImagesStorage =
            view.findViewById(
                R.id.tvImagesStorage
            )

        tvTempStorage =
            view.findViewById(
                R.id.tvTempStorage
            )

        tvOtherStorage =
            view.findViewById(
                R.id.tvOtherStorage
            )

        tvNoteCount =
            view.findViewById(
                R.id.tvNoteCount
            )

        tvFolderCount =
            view.findViewById(
                R.id.tvFolderCount
            )

        tvImageCount =
            view.findViewById(
                R.id.tvImageCount
            )

        tvHistoryCount =
            view.findViewById(
                R.id.tvHistoryCount
            )

        btnClearTemporary =
            view.findViewById(
                R.id.btnClearTemporary
            )

        tvClearTemporaryTitle =
            view.findViewById(
                R.id.tvClearTemporaryTitle
            )

        tvClearTemporarySubtitle =
            view.findViewById(
                R.id.tvClearTemporarySubtitle
            )

        view.findViewById<View>(
            R.id.btnStorageBack
        ).setOnClickListener {
            parentFragmentManager
                .popBackStack()
        }

        btnClearTemporary
            .setOnClickListener {
                if (currentTempBytes > 0L) {
                    clearTemporaryFiles(view)
                }
            }

        view.findViewById<View>(
            R.id.btnDeleteSavedContent
        ).setOnClickListener {
            showDeleteSavedContentConfirmation()
        }

        observeHistory()
        loadStorageDetails()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        if (
            parentFragmentManager
                .backStackEntryCount <= 1
        ) {
            setBottomNavigationVisible(
                true
            )
        }
    }

    private fun observeHistory() {
        AppDatabase
            .getDatabase(
                requireContext()
            )
            .appDao()
            .getAllScanHistory()
            .observe(
                viewLifecycleOwner
            ) { history ->
                val list =
                    history.orEmpty()

                tvHistoryCount.text =
                    plural(
                        list.size,
                        "scan"
                    )

                latestHistoryPaths =
                    list.mapNotNull {
                        it.imagePath
                            ?.takeIf { path ->
                                path.isNotBlank()
                            }
                    }

                loadStorageDetails()
            }
    }

    private fun loadStorageDetails() {
        val appContext =
            requireContext()
                .applicationContext

        val historySnapshot =
            latestHistoryPaths

        lifecycleScope.launch {
            val result =
                withContext(
                    Dispatchers.IO
                ) {
                    val database =
                        AppDatabase.getDatabase(
                            appContext
                        )

                    val notes =
                        database
                            .appDao()
                            .getAllNotes()
                            .first()

                    val folders =
                        database
                            .appDao()
                            .getAllFolders()
                            .first()

                    val allImagePaths =
                        buildList {
                            notes.forEach {
                                val path =
                                    it.imagePath

                                if (
                                    !path.isNullOrBlank()
                                ) {
                                    add(path)
                                }
                            }

                            addAll(
                                historySnapshot
                            )
                        }
                            .distinct()

                    val imageFiles =
                        allImagePaths
                            .map(::File)
                            .filter {
                                it.exists() &&
                                    it.isFile
                            }

                    val imageBytes =
                        imageFiles
                            .sumOf {
                                it.length()
                            }

                    val databaseBytes =
                        databaseSize(
                            appContext
                        )

                    val cacheBytes =
                        directorySize(
                            appContext.cacheDir
                        )

                    val filesBytes =
                        directorySize(
                            appContext.filesDir
                        )

                    val otherBytes =
                        (
                            filesBytes -
                                imageBytes
                            )
                            .coerceAtLeast(
                                0L
                            )

                    val totalBytes =
                        databaseBytes +
                            filesBytes +
                            cacheBytes

                    StorageInfo(
                        totalBytes =
                            totalBytes,

                        databaseBytes =
                            databaseBytes,

                        imagesBytes =
                            imageBytes,

                        tempBytes =
                            cacheBytes,

                        otherBytes =
                            otherBytes,

                        noteCount =
                            notes.size,

                        folderCount =
                            folders.size,

                        imageCount =
                            imageFiles.size
                    )
                }

            if (!isAdded) {
                return@launch
            }

            currentTempBytes =
                result.tempBytes

            tvTotalStorage.text =
                formatBytes(
                    result.totalBytes
                )

            tvDatabaseStorage.text =
                formatBytes(
                    result.databaseBytes
                )

            tvImagesStorage.text =
                formatBytes(
                    result.imagesBytes
                )

            tvTempStorage.text =
                formatBytes(
                    result.tempBytes
                )

            tvOtherStorage.text =
                formatBytes(
                    result.otherBytes
                )

            tvNoteCount.text =
                plural(
                    result.noteCount,
                    "note"
                )

            tvFolderCount.text =
                plural(
                    result.folderCount,
                    "folder"
                )

            tvImageCount.text =
                plural(
                    result.imageCount,
                    "image"
                )

            updateTemporaryActionState()
        }
    }

    private fun updateTemporaryActionState() {
        val hasTemporaryFiles =
            currentTempBytes > 0L

        btnClearTemporary.isEnabled =
            hasTemporaryFiles

        btnClearTemporary.isClickable =
            hasTemporaryFiles

        btnClearTemporary.alpha =
            if (hasTemporaryFiles) {
                1f
            } else {
                0.55f
            }

        tvClearTemporaryTitle.text =
            if (hasTemporaryFiles) {
                "Clear temporary files"
            } else {
                "No temporary files to clear"
            }

        tvClearTemporarySubtitle.text =
            if (hasTemporaryFiles) {
                "Removes ${formatBytes(currentTempBytes)} without deleting your saved notes"
            } else {
                "Your saved notes and images are not affected"
            }
    }

    private fun clearTemporaryFiles(
        anchor: View
    ) {
        val appContext =
            requireContext()
                .applicationContext

        lifecycleScope.launch {
            withContext(
                Dispatchers.IO
            ) {
                appContext
                    .cacheDir
                    .listFiles()
                    ?.forEach {
                        it.deleteRecursively()
                    }
            }

            if (!isAdded) {
                return@launch
            }

            showAppSnackbar(
                anchor,
                "Temporary files cleared"
            )

            loadStorageDetails()
        }
    }

    private fun showDeleteSavedContentConfirmation() {
        val dialog =
            android.app.Dialog(
                requireContext()
            )

        val dialogView =
            layoutInflater.inflate(
                R.layout.dialog_delete_saved_content,
                null
            )

        dialog.setContentView(
            dialogView
        )

        dialog.window
            ?.setBackgroundDrawableResource(
                android.R.color.transparent
            )

        dialog.setCancelable(true)

        dialogView
            .findViewById<View>(
                R.id.btnKeepData
            )
            .setOnClickListener {
                dialog.dismiss()
            }

        dialogView
            .findViewById<View>(
                R.id.btnDeleteSavedContentConfirm
            )
            .setOnClickListener {
                dialog.dismiss()
                deleteSavedContent()
            }

        dialog.show()

        dialog.window?.setLayout(
            (
                resources
                    .displayMetrics
                    .widthPixels *
                    0.88f
                ).toInt(),
            android.view.ViewGroup
                .LayoutParams
                .WRAP_CONTENT
        )
    }

    private fun deleteSavedContent() {
        val appContext =
            requireContext()
                .applicationContext

        val historySnapshot =
            latestHistoryPaths

        lifecycleScope.launch {
            withContext(
                Dispatchers.IO
            ) {
                val database =
                    AppDatabase.getDatabase(
                        appContext
                    )

                val notes =
                    database
                        .appDao()
                        .getAllNotes()
                        .first()

                val paths =
                    buildList {
                        notes.forEach {
                            val path =
                                it.imagePath

                            if (
                                !path.isNullOrBlank()
                            ) {
                                add(path)
                            }
                        }

                        addAll(
                            historySnapshot
                        )
                    }
                        .distinct()

                paths
                    .map(::File)
                    .forEach { file ->
                        if (
                            file.exists() &&
                            file.isFile
                        ) {
                            file.delete()
                        }
                    }

                /*
                 * Deletes Note, Folder and ScanHistory records,
                 * but intentionally keeps:
                 * - theme preference
                 * - onboarding/tutorial preference
                 * - legal agreement preference
                 */
                database.clearAllTables()

                appContext
                    .cacheDir
                    .listFiles()
                    ?.forEach {
                        it.deleteRecursively()
                    }
            }

            if (!isAdded) {
                return@launch
            }

            showAppSnackbar(
                requireView(),
                "Saved Note2Snap content deleted"
            )

            loadStorageDetails()
        }
    }

    private fun showAppSnackbar(
        anchor: View,
        message: String
    ) {
        Snackbar
            .make(
                anchor,
                message,
                Snackbar.LENGTH_SHORT
            )
            .setBackgroundTint(
                ContextCompat.getColor(
                    requireContext(),
                    R.color.nts_text
                )
            )
            .setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    R.color.nts_surface
                )
            )
            .show()
    }

    private fun databaseSize(
        context:
            android.content.Context
    ): Long {
        val db =
            context.getDatabasePath(
                "note2snap_database"
            )

        var size =
            if (db.exists()) {
                db.length()
            } else {
                0L
            }

        val wal =
            File(
                db.absolutePath +
                    "-wal"
            )

        val shm =
            File(
                db.absolutePath +
                    "-shm"
            )

        if (wal.exists()) {
            size +=
                wal.length()
        }

        if (shm.exists()) {
            size +=
                shm.length()
        }

        return size
    }

    private fun directorySize(
        directory: File?
    ): Long {
        if (
            directory == null ||
            !directory.exists()
        ) {
            return 0L
        }

        if (directory.isFile) {
            return directory.length()
        }

        return directory
            .listFiles()
            ?.sumOf {
                directorySize(it)
            }
            ?: 0L
    }

    private fun formatBytes(
        bytes: Long
    ): String =
        Formatter.formatFileSize(
            requireContext(),
            bytes
        )

    private fun plural(
        count: Int,
        name: String
    ): String =
        if (count == 1) {
            "1 $name"
        } else {
            "$count ${name}s"
        }

    private fun setBottomNavigationVisible(
        visible: Boolean
    ) {
        val state =
            if (visible) {
                View.VISIBLE
            } else {
                View.GONE
            }

        activity
            ?.findViewById<View>(
                R.id.bottomNavContainer
            )
            ?.visibility =
            state

        activity
            ?.findViewById<View>(
                R.id.scanFab
            )
            ?.visibility =
            state
    }

    private data class StorageInfo(
        val totalBytes: Long,
        val databaseBytes: Long,
        val imagesBytes: Long,
        val tempBytes: Long,
        val otherBytes: Long,
        val noteCount: Int,
        val folderCount: Int,
        val imageCount: Int
    )
}
