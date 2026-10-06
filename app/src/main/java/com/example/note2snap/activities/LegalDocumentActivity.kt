package com.example.note2snap.activities

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.note2snap.R
import com.google.android.material.button.MaterialButton

class LegalDocumentActivity : AppCompatActivity() {

    companion object {

        const val EXTRA_DOCUMENT =
            "LEGAL_DOCUMENT"

        const val DOC_TERMS =
            "TERMS"

        const val DOC_PRIVACY =
            "PRIVACY"
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        val type =
            intent.getStringExtra(
                EXTRA_DOCUMENT
            )

        val isPrivacy =
            type == DOC_PRIVACY

        setContentView(
            buildScreen(
                isPrivacy
            )
        )
    }

    private fun buildScreen(
        privacy: Boolean
    ): ScrollView {

        val scroll =
            ScrollView(this).apply {

                isFillViewport =
                    true

                setBackgroundColor(
                    ContextCompat.getColor(
                        this@LegalDocumentActivity,
                        R.color.nts_background
                    )
                )
            }

        val content =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(24),
                    dp(24),
                    dp(24),
                    dp(38)
                )
            }

        scroll.addView(
            content
        )

        val back =
            MaterialButton(this).apply {

                text =
                    "‹  Back"

                isAllCaps =
                    false

                textSize =
                    12f

                typeface =
                    ResourcesCompat.getFont(
                        this@LegalDocumentActivity,
                        R.font.poppins_medium
                    )

                setTextColor(
                    ContextCompat.getColor(
                        this@LegalDocumentActivity,
                        R.color.nts_blue
                    )
                )

                backgroundTintList =
                    ContextCompat.getColorStateList(
                        this@LegalDocumentActivity,
                        android.R.color.transparent
                    )

                gravity =
                    Gravity.START or
                            Gravity.CENTER_VERTICAL

                setOnClickListener {

                    finish()
                }
            }

        content.addView(
            back,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
            )
        )

        content.addView(
            TextView(this).apply {

                text =
                    if (privacy) {
                        "Privacy & Data Protection"
                    } else {
                        "Terms of Use & User Agreement"
                    }

                textSize =
                    28f

                typeface =
                    ResourcesCompat.getFont(
                        this@LegalDocumentActivity,
                        R.font.apple_garamond_bold
                    )

                setTextColor(
                    ContextCompat.getColor(
                        this@LegalDocumentActivity,
                        R.color.nts_text
                    )
                )
            }
        )

        content.addView(
            TextView(this).apply {

                text =
                    "Note2Snap"

                textSize =
                    11f

                typeface =
                    ResourcesCompat.getFont(
                        this@LegalDocumentActivity,
                        R.font.poppins_medium
                    )

                setTextColor(
                    ContextCompat.getColor(
                        this@LegalDocumentActivity,
                        R.color.nts_blue
                    )
                )

                setPadding(
                    0,
                    dp(2),
                    0,
                    dp(20)
                )
            }
        )

        if (privacy) {

            addPrivacyContent(
                content
            )

        } else {

            addTermsContent(
                content
            )
        }

        return scroll
    }

    private fun addTermsContent(
        content: LinearLayout
    ) {

        paragraph(
            content,
            "Agreement to the Terms",
            """
            By using Note2Snap, the user agrees to follow these Terms of Use and use the application responsibly for academic, educational, or personal note-taking purposes.
            """.trimIndent()
        )

        paragraph(
            content,
            "1. Proper Use of Note2Snap",
            """
            Note2Snap is designed to capture or import whiteboard images and convert recognized content into organized digital notes.

            Users must use the application only for lawful and authorized purposes.
            """.trimIndent()
        )

        paragraph(
            content,
            "2. Permission to Capture Content",
            """
            Users are responsible for obtaining permission before photographing, scanning, storing, exporting, or sharing content that belongs to another person.

            This includes materials belonging to classmates, instructors, professors, schools, organizations, or other individuals.
            """.trimIndent()
        )

        paragraph(
            content,
            "3. Academic and Personal Information",
            """
            Whiteboards and imported images may contain names, student numbers, grades, schedules, class discussions, instructor materials, or other information.

            Users should avoid capturing confidential, restricted, or sensitive information unless they are authorized to do so.
            """.trimIndent()
        )

        paragraph(
            content,
            "4. Recognition Accuracy",
            """
            Note2Snap uses image-processing and text-recognition technologies.

            Recognition results may contain errors. Users should review generated notes before relying on them for studying, submitting academic work, sharing, or exporting.
            """.trimIndent()
        )

        paragraph(
            content,
            "5. User Responsibility",
            """
            The user remains responsible for the images, notes, and other materials processed through the application.

            Note2Snap should not be used to intentionally copy, distribute, or disclose private, confidential, copyrighted, or restricted material without authorization.
            """.trimIndent()
        )

        paragraph(
            content,
            "6. Camera and Gallery Access",
            """
            Note2Snap may request access to the device camera or gallery only when needed to capture or import images.

            Users may deny or revoke these permissions through their device settings.
            """.trimIndent()
        )

        paragraph(
            content,
            "7. Generated Notes",
            """
            Generated notes are provided as an organizational and study aid.

            Users should verify important information, formulas, names, dates, diagrams, and other recognized content before use.
            """.trimIndent()
        )

        paragraph(
            content,
            "8. Changes to the Application",
            """
            Features, interfaces, and application functions may be changed or improved as Note2Snap continues to be developed.
            """.trimIndent()
        )

        paragraph(
            content,
            "9. Acceptance",
            """
            By selecting Agree & Continue, the user confirms that they have read and understood these Terms of Use and agree to use Note2Snap responsibly.
            """.trimIndent()
        )
    }

    private fun addPrivacyContent(
        content: LinearLayout
    ) {

        paragraph(
            content,
            "Privacy Commitment",
            """
            Note2Snap is designed with data minimization and user control in mind.

            Because scanned whiteboards may contain information about students, classmates, instructors, or professors, users should only capture information they are authorized to access.
            """.trimIndent()
        )

        paragraph(
            content,
            "1. Information Processed",
            """
            Note2Snap may process whiteboard images, imported photos, recognized text, generated notes, diagrams, titles, folders, and other information needed to provide its note-generation functions.
            """.trimIndent()
        )

        paragraph(
            content,
            "2. Local-First Storage",
            """
            Notes and scan information are kept under the user's control in the application's local storage unless the user intentionally chooses to export or share them.

            Note2Snap should not automatically publish a user's notes or scans.
            """.trimIndent()
        )

        paragraph(
            content,
            "3. Data Minimization",
            """
            Note2Snap does not require users to enter unnecessary personal information for its core whiteboard-scanning functions.

            Users are encouraged not to capture student numbers, grades, contact information, private conversations, or other sensitive personal information unless necessary and authorized.
            """.trimIndent()
        )

        paragraph(
            content,
            "4. Camera and Gallery Permissions",
            """
            Camera access is used for capturing whiteboard images.

            Gallery or media access is used when the user chooses to import an existing image.

            These permissions should only be used for the functions requested by the user.
            """.trimIndent()
        )

        paragraph(
            content,
            "5. Student and Instructor Information",
            """
            Users must obtain permission before capturing, storing, exporting, or sharing private or restricted information belonging to students, instructors, professors, or the school.

            This includes grades, attendance information, student records, examination materials, confidential discussions, and restricted instructional content.
            """.trimIndent()
        )

        paragraph(
            content,
            "6. User Control",
            """
            Users can review and edit recognized notes before saving them.

            Saved content can be organized, exported, or deleted through the application according to the functions available in Note2Snap.
            """.trimIndent()
        )

        paragraph(
            content,
            "7. Sharing and Export",
            """
            Note2Snap should only share or export content after an intentional action by the user.

            Users remain responsible for ensuring that they have permission to distribute any information contained in an exported note or document.
            """.trimIndent()
        )

        paragraph(
            content,
            "8. Data Protection",
            """
            Note2Snap uses reasonable application-level safeguards such as app-controlled storage, limited device permissions, user-controlled sharing, and user-controlled deletion to reduce unnecessary exposure of information.

            Users should also protect their own device using appropriate screen-lock or device-security features.
            """.trimIndent()
        )

        paragraph(
            content,
            "9. Data Retention",
            """
            Information should only be retained for as long as it is useful or necessary for the user's academic or personal purpose.

            Users may delete notes that they no longer need.
            """.trimIndent()
        )

        paragraph(
            content,
            "10. Privacy Responsibility",
            """
            Privacy protection is a shared responsibility.

            Note2Snap provides tools for processing and organizing notes, while users are responsible for ensuring that the information they capture, store, export, or share is handled appropriately and with permission.
            """.trimIndent()
        )
    }

    private fun paragraph(
        parent: LinearLayout,
        heading: String,
        body: String
    ) {

        parent.addView(
            TextView(this).apply {

                text =
                    heading

                textSize =
                    14f

                typeface =
                    ResourcesCompat.getFont(
                        this@LegalDocumentActivity,
                        R.font.poppins_semibold
                    )

                setTextColor(
                    ContextCompat.getColor(
                        this@LegalDocumentActivity,
                        R.color.nts_text
                    )
                )

                setPadding(
                    0,
                    dp(12),
                    0,
                    dp(5)
                )
            }
        )

        parent.addView(
            TextView(this).apply {

                text =
                    body

                textSize =
                    11f

                typeface =
                    ResourcesCompat.getFont(
                        this@LegalDocumentActivity,
                        R.font.poppins_regular
                    )

                setTextColor(
                    ContextCompat.getColor(
                        this@LegalDocumentActivity,
                        R.color.nts_text_secondary
                    )
                )

                setLineSpacing(
                    0f,
                    1.2f
                )

                setPadding(
                    0,
                    0,
                    0,
                    dp(5)
                )
            }
        )
    }

    private fun dp(
        value: Int
    ): Int {

        return (
                value *
                        resources.displayMetrics.density
                ).toInt()
    }
}