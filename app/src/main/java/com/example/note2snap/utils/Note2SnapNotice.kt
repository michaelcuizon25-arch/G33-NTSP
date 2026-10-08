package com.example.note2snap.utils

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.note2snap.R
import com.google.android.material.card.MaterialCardView

object Note2SnapNotice {

    fun show(
        anchor: View,
        title: String,
        message: String = "",
        symbol: String = "✓"
    ) {
        val host =
            anchor.rootView
                .findViewById<ViewGroup>(
                    android.R.id.content
                )
                ?: return

        host.findViewWithTag<View>(
            "note2snap_notice"
        )?.let {
            host.removeView(
                it
            )
        }

        val density =
            anchor.resources
                .displayMetrics
                .density

        fun dp(
            value: Int
        ): Int =
            (
                value *
                density
            ).toInt()

        val card =
            MaterialCardView(
                anchor.context
            ).apply {
                tag =
                    "note2snap_notice"

                radius =
                    dp(
                        18
                    ).toFloat()

                cardElevation =
                    dp(
                        7
                    ).toFloat()

                strokeWidth =
                    dp(
                        1
                    )

                strokeColor =
                    ContextCompat.getColor(
                        context,
                        R.color.nts_blue_line
                    )

                setCardBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        R.color.nts_surface
                    )
                )
            }

        val row =
            LinearLayout(
                anchor.context
            ).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(
                        12
                    ),
                    dp(
                        10
                    ),
                    dp(
                        14
                    ),
                    dp(
                        10
                    )
                )
            }

        val icon =
            TextView(
                anchor.context
            ).apply {
                text =
                    symbol

                gravity =
                    Gravity.CENTER

                textSize =
                    15f

                typeface =
                    ResourcesCompat.getFont(
                        context,
                        R.font.poppins_semibold
                    )

                setTextColor(
                    ContextCompat.getColor(
                        context,
                        R.color.nts_blue
                    )
                )

                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.OVAL

                        setColor(
                            ContextCompat.getColor(
                                context,
                                R.color.nts_surface_blue_soft
                            )
                        )
                    }
            }

        row.addView(
            icon,
            LinearLayout.LayoutParams(
                dp(
                    36
                ),
                dp(
                    36
                )
            )
        )

        val textWrap =
            LinearLayout(
                anchor.context
            ).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(
                        11
                    ),
                    0,
                    0,
                    0
                )
            }

        textWrap.addView(
            TextView(
                anchor.context
            ).apply {
                text =
                    title

                textSize =
                    11f

                maxLines =
                    1

                typeface =
                    ResourcesCompat.getFont(
                        context,
                        R.font.poppins_semibold
                    )

                setTextColor(
                    ContextCompat.getColor(
                        context,
                        R.color.nts_text
                    )
                )
            }
        )

        if (
            message.isNotBlank()
        ) {
            textWrap.addView(
                TextView(
                    anchor.context
                ).apply {
                    text =
                        message

                    textSize =
                        9f

                    maxLines =
                        2

                    typeface =
                        ResourcesCompat.getFont(
                            context,
                            R.font.poppins_regular
                        )

                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.nts_text_secondary
                        )
                    )
                }
            )
        }

        row.addView(
            textWrap,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
                1f
            )
        )

        card.addView(
            row
        )

        val params =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams
                    .MATCH_PARENT,
                FrameLayout.LayoutParams
                    .WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                marginStart =
                    dp(
                        18
                    )

                marginEnd =
                    dp(
                        18
                    )

                bottomMargin =
                    dp(
                        92
                    )
            }

        host.addView(
            card,
            params
        )

        card.alpha =
            0f

        card.translationY =
            dp(
                8
            ).toFloat()

        card.animate()
            .alpha(
                1f
            )
            .translationY(
                0f
            )
            .setDuration(
                110L
            )
            .start()

        card.postDelayed(
            {
                if (
                    card.parent !=
                    null
                ) {
                    card.animate()
                        .alpha(
                            0f
                        )
                        .translationY(
                            dp(
                                6
                            ).toFloat()
                        )
                        .setDuration(
                            100L
                        )
                        .withEndAction {
                            (
                                card.parent as?
                                    ViewGroup
                                )
                                ?.removeView(
                                    card
                                )
                        }
                        .start()
                }
            },
            1800L
        )
    }
}
