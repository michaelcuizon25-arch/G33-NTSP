package com.example.note2snap.activities

import android.content.Context
import androidx.appcompat.app.AlertDialog

object TermsDialog {
    fun show(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Terms of Use")
            .setMessage(
                "Use Note2Snap responsibly for capturing and organizing notes. " +
                    "Only capture content you are allowed to keep or use. " +
                    "Review generated notes before relying on them for school work."
            )
            .setPositiveButton("Close", null)
            .show()
    }
}

object PrivacyDialog {
    fun show(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Privacy Notice")
            .setMessage(
                "Note2Snap stores your notes and app files on your device. " +
                    "Be careful when capturing boards that contain names, grades, " +
                    "student information, or other private classroom details. " +
                    "Only keep or share information you are allowed to use."
            )
            .setPositiveButton("Close", null)
            .show()
    }
}
