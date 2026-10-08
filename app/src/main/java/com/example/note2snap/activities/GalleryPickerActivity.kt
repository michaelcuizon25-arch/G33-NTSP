package com.example.note2snap.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.HapticFeedbackConstants
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.adapter.GalleryPhotoAdapter
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryPickerActivity :
    AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_URIS =
            "SELECTED_URIS"

        private const val MAX_SELECTION =
            10
    }

    private lateinit var gridPhotos:
        GridView

    private lateinit var loadingView:
        View

    private lateinit var permissionView:
        View

    private lateinit var emptyView:
        View

    private lateinit var bottomBar:
        MaterialCardView

    private lateinit var tvSelectedCount:
        TextView

    private lateinit var tvBottomCount:
        TextView

    private lateinit var btnAdd:
        TextView

    private lateinit var adapter:
        GalleryPhotoAdapter

    private val photos =
        mutableListOf<Uri>()

    private val selectedUris =
        mutableListOf<Uri>()

    private val requestPermissions =
        registerForActivityResult(
            ActivityResultContracts
                .RequestMultiplePermissions()
        ) {
            if (
                hasGalleryAccess()
            ) {
                showGallery()
                loadPhotos()
            } else {
                showPermissionState()
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_gallery_picker
        )

        window.statusBarColor =
            ContextCompat.getColor(
                this,
                R.color.nts_background
            )

        window.navigationBarColor =
            ContextCompat.getColor(
                this,
                R.color.nts_background
            )

        @Suppress("DEPRECATION")
        overridePendingTransition(
            R.anim.gallery_picker_enter,
            R.anim.gallery_picker_stay
        )

        bindViews()
        setupGrid()
        setupActions()

        if (
            hasGalleryAccess()
        ) {
            showGallery()
            loadPhotos()
        } else {
            showPermissionState()
        }
    }

    private fun bindViews() {
        gridPhotos =
            findViewById(
                R.id.gridGalleryPhotos
            )

        loadingView =
            findViewById(
                R.id.galleryLoading
            )

        permissionView =
            findViewById(
                R.id.galleryPermissionState
            )

        emptyView =
            findViewById(
                R.id.galleryEmptyState
            )

        bottomBar =
            findViewById(
                R.id.galleryBottomBar
            )

        tvSelectedCount =
            findViewById(
                R.id.tvGallerySelectedCount
            )

        tvBottomCount =
            findViewById(
                R.id.tvGalleryBottomCount
            )

        btnAdd =
            findViewById(
                R.id.btnGalleryAdd
            )
    }

    private fun setupGrid() {
        adapter =
            GalleryPhotoAdapter(
                context =
                    this,
                photos =
                    photos,
                selectionIndex = {
                    uri ->

                    selectedUris
                        .indexOf(
                            uri
                        )
                        .let {
                            if (
                                it >=
                                0
                            ) {
                                it +
                                    1
                            } else {
                                0
                            }
                        }
                }
            )

        gridPhotos.adapter =
            adapter

        gridPhotos.setOnItemClickListener {
                _,
                itemView,
                position,
                _ ->

            val uri =
                photos.getOrNull(
                    position
                )
                    ?: return@setOnItemClickListener

            toggleSelection(
                uri,
                itemView
            )
        }
    }

    private fun setupActions() {
        findViewById<View>(
            R.id.btnGalleryBack
        ).setOnClickListener {
            finishWithAnimation()
        }

        findViewById<View>(
            R.id.btnGalleryAllow
        ).setOnClickListener {
            requestGalleryPermission()
        }

        findViewById<View>(
            R.id.btnGalleryClear
        ).setOnClickListener {
            if (
                selectedUris
                    .isEmpty()
            ) {
                return@setOnClickListener
            }

            selectedUris.clear()
            adapter.notifyDataSetChanged()
            updateSelectionUi(
                animate =
                    true
            )
        }

        btnAdd.setOnClickListener {
            btnAdd.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP
            )

            if (
                selectedUris
                    .isEmpty()
            ) {
                return@setOnClickListener
            }

            btnAdd
                .animate()
                .scaleX(
                    0.96f
                )
                .scaleY(
                    0.96f
                )
                .setDuration(
                    70L
                )
                .withEndAction {
                    btnAdd
                        .animate()
                        .scaleX(
                            1f
                        )
                        .scaleY(
                            1f
                        )
                        .setDuration(
                            90L
                        )
                        .start()

                    returnSelection()
                }
                .start()
        }
    }

    private fun toggleSelection(
        uri: Uri,
        itemView: View
    ) {
        itemView.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP
        )

        val existingIndex =
            selectedUris.indexOf(
                uri
            )

        if (
            existingIndex >=
            0
        ) {
            selectedUris.removeAt(
                existingIndex
            )

        } else {
            if (
                selectedUris.size >=
                MAX_SELECTION
            ) {
                showSelectionLimitHint()
                return
            }

            selectedUris.add(
                uri
            )
        }

        adapter.notifyDataSetChanged()

        updateSelectionUi(
            animate =
                true
        )
    }

    private fun updateSelectionUi(
        animate: Boolean
    ) {
        val count =
            selectedUris.size

        tvSelectedCount.text =
            "$count/$MAX_SELECTION"

        tvBottomCount.text =
            when (count) {
                0 ->
                    "Select whiteboard photos"

                1 ->
                    "1 photo selected"

                else ->
                    "$count photos selected"
            }

        btnAdd.text =
            if (
                count <=
                1
            ) {
                "Add photo"
            } else {
                "Add $count"
            }

        val shouldShow =
            count >
                0

        bottomBar.visibility =
            if (
                shouldShow
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        bottomBar.alpha =
            1f
        bottomBar.translationY =
            0f
    }

    private fun showSelectionLimitHint() {
        val hint =
            findViewById<TextView>(
                R.id.tvGalleryHint
            )

        val oldText =
            hint.text

        hint.text =
            "Maximum of 10 photos per batch"

        hint.setTextColor(
            Color.parseColor(
                "#C45151"
            )
        )

        hint
            .animate()
            .alpha(
                0.55f
            )
            .setDuration(
                80L
            )
            .withEndAction {
                hint
                    .animate()
                    .alpha(
                        1f
                    )
                    .setDuration(
                        120L
                    )
                    .start()
            }
            .start()

        hint.postDelayed(
            {
                if (
                    !isFinishing
                ) {
                    hint.text =
                        oldText

                    hint.setTextColor(
                        ContextCompat
                            .getColor(
                                this,
                                R.color.nts_text_secondary
                            )
                    )
                }
            },
            1800L
        )
    }

    private fun loadPhotos() {
        loadingView.visibility =
            View.VISIBLE

        emptyView.visibility =
            View.GONE

        gridPhotos.visibility =
            View.INVISIBLE

        lifecycleScope.launch(
            Dispatchers.IO
        ) {
            val loaded =
                queryRecentImages()

            withContext(
                Dispatchers.Main
            ) {
                photos.clear()
                photos.addAll(
                    loaded
                )

                adapter.notifyDataSetChanged()

                loadingView.visibility =
                    View.GONE

                if (
                    photos.isEmpty()
                ) {
                    emptyView.visibility =
                        View.VISIBLE

                    gridPhotos.visibility =
                        View.GONE
                } else {
                    emptyView.visibility =
                        View.GONE

                    gridPhotos.visibility =
                        View.VISIBLE

                    gridPhotos.alpha =
                        1f

                    gridPhotos.translationY =
                        0f
                }
            }
        }
    }

    private fun queryRecentImages():
        List<Uri> {
        val result =
            mutableListOf<Uri>()

        val collection =
            MediaStore.Images.Media
                .EXTERNAL_CONTENT_URI

        val projection =
            arrayOf(
                MediaStore.Images.Media._ID
            )

        val sortOrder =
            "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver
            .query(
                collection,
                projection,
                null,
                null,
                sortOrder
            )
            ?.use {
                    cursor ->

                val idColumn =
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Images.Media._ID
                    )

                while (
                    cursor.moveToNext() &&
                    result.size <
                    500
                ) {
                    val id =
                        cursor.getLong(
                            idColumn
                        )

                    result.add(
                        Uri.withAppendedPath(
                            collection,
                            id.toString()
                        )
                    )
                }
            }

        return result
    }

    private fun requestGalleryPermission() {
        val permissions =
            when {
                Build.VERSION.SDK_INT >=
                    34 ->
                    arrayOf(
                        Manifest.permission
                            .READ_MEDIA_IMAGES,
                        Manifest.permission
                            .READ_MEDIA_VISUAL_USER_SELECTED
                    )

                Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU ->
                    arrayOf(
                        Manifest.permission
                            .READ_MEDIA_IMAGES
                    )

                else ->
                    arrayOf(
                        Manifest.permission
                            .READ_EXTERNAL_STORAGE
                    )
            }

        requestPermissions.launch(
            permissions
        )
    }

    private fun hasGalleryAccess():
        Boolean {
        return when {
            Build.VERSION.SDK_INT >=
                34 -> {
                ContextCompat
                    .checkSelfPermission(
                        this,
                        Manifest.permission
                            .READ_MEDIA_IMAGES
                    ) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat
                        .checkSelfPermission(
                            this,
                            Manifest.permission
                                .READ_MEDIA_VISUAL_USER_SELECTED
                        ) ==
                    PackageManager.PERMISSION_GRANTED
            }

            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU ->
                ContextCompat
                    .checkSelfPermission(
                        this,
                        Manifest.permission
                            .READ_MEDIA_IMAGES
                    ) ==
                    PackageManager.PERMISSION_GRANTED

            else ->
                ContextCompat
                    .checkSelfPermission(
                        this,
                        Manifest.permission
                            .READ_EXTERNAL_STORAGE
                    ) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showGallery() {
        permissionView.visibility =
            View.GONE

        gridPhotos.visibility =
            View.VISIBLE
    }

    private fun showPermissionState() {
        loadingView.visibility =
            View.GONE

        gridPhotos.visibility =
            View.GONE

        emptyView.visibility =
            View.GONE

        permissionView.visibility =
            View.VISIBLE

        permissionView.alpha =
            1f

        permissionView.translationY =
            0f
    }

    private fun returnSelection() {
        val data =
            Intent().putStringArrayListExtra(
                EXTRA_SELECTED_URIS,
                ArrayList(
                    selectedUris.map {
                        it.toString()
                    }
                )
            )

        setResult(
            Activity.RESULT_OK,
            data
        )

        finishWithAnimation()
    }

    private fun finishWithAnimation() {
        finish()

        @Suppress("DEPRECATION")
        overridePendingTransition(
            R.anim.gallery_picker_stay,
            R.anim.gallery_picker_exit
        )
    }

    override fun onBackPressed() {
        finishWithAnimation()
    }
}
