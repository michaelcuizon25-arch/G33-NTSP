package com.example.note2snap.activities

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import com.example.note2snap.R

class WarningDialog(
    private val context: Context
) {

    fun show(
        title: String,
        message: String,
        primaryText: String = "Try again",
        closeText: String = "Close",
        onCloseClicked: (() -> Unit)? = null,
        onPrimaryClicked: (() -> Unit)? = null
    ) {
        val dialog =
            Dialog(
                context
            )

        dialog.requestWindowFeature(
            Window.FEATURE_NO_TITLE
        )

        val view =
            LayoutInflater.from(
                context
            ).inflate(
                R.layout.dialog_custom_warning,
                null,
                false
            )

        dialog.setContentView(
            view
        )

        dialog.window
            ?.setBackgroundDrawable(
                ColorDrawable(
                    Color.TRANSPARENT
                )
            )

        view.findViewById<TextView>(
            R.id.tvWarningTitle
        ).text =
            title

        view.findViewById<TextView>(
            R.id.tvWarningMessage
        ).text =
            message

        view.findViewById<TextView>(
            R.id.btnClose
        ).apply {
            text = closeText

            setOnClickListener {
                dialog.dismiss()
                onCloseClicked?.invoke()
            }
        }

        view.findViewById<TextView>(
            R.id.btnGotIt
        ).apply {
            text =
                primaryText

            setOnClickListener {
                dialog.dismiss()
                onPrimaryClicked
                    ?.invoke()
            }
        }

        dialog.show()

        dialog.window
            ?.setLayout(
                (
                        context.resources
                            .displayMetrics
                            .widthPixels *
                                0.90f
                        ).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
    }
}