package com.example.note2snap.activities

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.model.BlockType
import com.example.note2snap.model.NoteBlock
import com.example.note2snap.model.StructuredNote
import com.example.note2snap.utils.WhiteboardRuleEngine
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var navHome: LinearLayout
    private lateinit var navNotes: LinearLayout
    private lateinit var navHistory: LinearLayout
    private lateinit var navSettings: LinearLayout
    private lateinit var scanFab: MaterialCardView

    private lateinit var iconHome: ImageView
    private lateinit var iconNotes: ImageView
    private lateinit var iconHistory: ImageView
    private lateinit var iconSettings: ImageView

    private lateinit var textHome: TextView
    private lateinit var textNotes: TextView
    private lateinit var textHistory: TextView
    private lateinit var textSettings: TextView

    private lateinit var bottomNavContainer: View

    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressText: TextView

    private val pickMultipleImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            processBatchImages(uris)
        } else {
            Toast.makeText(this, getString(R.string.batch_no_images_selected), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPref = getSharedPreferences("AppSettings", MODE_PRIVATE)

        val isSystemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        val isDarkModeSaved = sharedPref.getBoolean("DARK_MODE", isSystemDark)

        val targetMode = if (isDarkModeSaved) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }

        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()

        if (savedInstanceState == null) {
            selectTab(R.id.navHome)
        }
    }

    private fun initViews() {
        bottomNavContainer = findViewById(R.id.bottomNavContainer)

        navHome = findViewById(R.id.navHome)
        navNotes = findViewById(R.id.navNotes)
        navHistory = findViewById(R.id.navHistory)
        navSettings = findViewById(R.id.navSettings)

        scanFab = findViewById(R.id.scanFab)

        iconHome = findViewById(R.id.iconHome)
        iconNotes = findViewById(R.id.iconNotes)
        iconHistory = findViewById(R.id.iconHistory)
        iconSettings = findViewById(R.id.iconSettings)

        textHome = findViewById(R.id.textHome)
        textNotes = findViewById(R.id.textNotes)
        textHistory = findViewById(R.id.textHistory)
        textSettings = findViewById(R.id.textSettings)

        progressBar = findViewById(R.id.progressBar)
        tvProgressText = findViewById(R.id.tvProgressText)
    }

    override fun onResume() {
        super.onResume()

        val sharedPref = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val shouldBeDark = sharedPref.getBoolean("DARK_MODE", false)

        val isCurrentlyDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        if (shouldBeDark != isCurrentlyDark) {
            AppCompatDelegate.setDefaultNightMode(
                if (shouldBeDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun setupClickListeners() {
        navHome.setOnClickListener { selectTab(R.id.navHome) }
        navNotes.setOnClickListener { selectTab(R.id.navNotes) }
        navHistory.setOnClickListener { selectTab(R.id.navHistory) }
        navSettings.setOnClickListener { selectTab(R.id.navSettings) }

        scanFab.setOnClickListener { openScan() }

        scanFab.setOnLongClickListener {
            launchBatchPicker()
            true
        }
    }

    fun launchBatchPicker() {
        pickMultipleImages.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun processBatchImages(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.VISIBLE
                tvProgressText.visibility = View.VISIBLE
                tvProgressText.text = getString(R.string.batch_progress_initial, uris.size)
            }

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val combinedBlocks = mutableListOf<NoteBlock>()

            uris.forEachIndexed { index, uri ->
                try {
                    withContext(Dispatchers.Main) {
                        tvProgressText.text = getString(R.string.batch_progress_current, index + 1, uris.size)
                    }

                    val image = InputImage.fromFilePath(this@MainActivity, uri)
                    val visionText = recognizer.process(image).await()
                    val pageResult = WhiteboardRuleEngine.process(visionText)

                    combinedBlocks.add(
                        NoteBlock(
                            rawText = getString(R.string.batch_page_header, index + 1),
                            type = BlockType.SECTION_HEADER,
                            formattedText = getString(R.string.batch_page_header_html, index + 1),
                            boundingBox = null
                        )
                    )

                    if (pageResult.blocks.isEmpty()) {
                        combinedBlocks.add(
                            NoteBlock(
                                rawText = getString(R.string.batch_no_text_raw),
                                type = BlockType.REGULAR_TEXT,
                                formattedText = getString(R.string.batch_no_text_html),
                                boundingBox = null
                            )
                        )
                    } else {
                        combinedBlocks.addAll(pageResult.blocks)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val batchNote = StructuredNote(
                title = getString(R.string.batch_note_title, uris.size),
                blocks = combinedBlocks
            )

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                tvProgressText.visibility = View.GONE

                handleProcessedNote(batchNote)
            }
        }
    }

    private fun handleProcessedNote(note: StructuredNote) {
        Toast.makeText(
            this,
            getString(R.string.batch_success, note.blocks.size),
            Toast.LENGTH_SHORT
        ).show()

        val intent = Intent(this, SaveFolderActivity::class.java).apply {
            putExtra("EXTRA_NOTE_TITLE", note.title)
        }
        startActivity(intent)
    }

    fun selectTab(itemId: Int) {
        bottomNavContainer.visibility = View.VISIBLE
        scanFab.visibility = View.VISIBLE

        val fragment: Fragment = when (itemId) {
            R.id.navHome, R.id.nav_home -> HomeFragment()
            R.id.navNotes, R.id.nav_notes -> NotesFragment()
            R.id.navHistory, R.id.nav_history -> HistoryFragment()
            R.id.navSettings, R.id.nav_settings -> SettingsFragment()
            else -> HomeFragment()
        }

        updateNavUI(itemId)

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    fun openScan() {
        bottomNavContainer.visibility = View.GONE
        scanFab.visibility = View.GONE

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.fragmentContainer, ScanFragment())
            .commit()
    }

    private fun updateNavUI(selectedId: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.nav_active)
        val inactiveColor = ContextCompat.getColor(this, R.color.nav_inactive)

        resetTabUI(navHome, iconHome, textHome, inactiveColor)
        resetTabUI(navNotes, iconNotes, textNotes, inactiveColor)
        resetTabUI(navHistory, iconHistory, textHistory, inactiveColor)
        resetTabUI(navSettings, iconSettings, textSettings, inactiveColor)

        when (selectedId) {
            R.id.navHome, R.id.nav_home -> setTabActive(navHome, iconHome, textHome, activeColor)
            R.id.navNotes, R.id.nav_notes -> setTabActive(navNotes, iconNotes, textNotes, activeColor)
            R.id.navHistory, R.id.nav_history -> setTabActive(navHistory, iconHistory, textHistory, activeColor)
            R.id.navSettings, R.id.nav_settings -> setTabActive(navSettings, iconSettings, textSettings, activeColor)
        }
    }

    private fun resetTabUI(
        container: LinearLayout,
        icon: ImageView,
        text: TextView,
        color: Int
    ) {
        container.setBackgroundResource(R.drawable.bg_nav_item_inactive)
        icon.setColorFilter(color)
        text.setTextColor(color)
    }

    private fun setTabActive(
        container: LinearLayout,
        icon: ImageView,
        text: TextView,
        color: Int
    ) {
        container.setBackgroundResource(R.drawable.bg_nav_item_active)
        icon.setColorFilter(color)
        text.setTextColor(color)
    }
}