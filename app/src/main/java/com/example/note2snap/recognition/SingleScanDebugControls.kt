package com.example.note2snap.recognition

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.Toast

/** Installs only the debug long-press; the tab's normal click behavior is untouched. */
internal object SingleScanDebugControls {
    fun install(tab: View, context: Context, isBusy: () -> Boolean) {
        val settings = SingleScanPreprocessingSettings(context)
        if (!settings.isDebugEnabled) return
        tab.setOnLongClickListener {
            if (!isBusy()) showSelector(context, settings)
            true
        }
    }

    private fun showSelector(context: Context, settings: SingleScanPreprocessingSettings) {
        val modes = arrayOf(CrnnPreprocessingMode.LEGACY, CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE)
        var selected = modes.indexOf(settings.selectedMode)
        AlertDialog.Builder(context)
            .setTitle("Single CRNN preprocessing (debug)")
            .setSingleChoiceItems(arrayOf("LEGACY (default)", "SIMPLE_HTR_REFERENCE"), selected) { _, index -> selected = index }
            .setPositiveButton("Use mode") { _, _ ->
                settings.select(modes[selected])
                Toast.makeText(context, "Single scans: ${modes[selected]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
