package com.example.note2snap.adapter

import android.view.View
import androidx.viewpager2.widget.ViewPager2

/**
 * Creates a 3D stacked depth animation where cards layer behind each other with scaling and elevation.
 */
class StackNoteTransformer(
    private val maxVisibleItems: Int = 3,
    private val scaleOffset: Float = 0.08f,       // Scale reduction per stacked layer
    private val verticalOffsetDp: Float = 20f     // Vertical offset between stacked cards
) : ViewPager2.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        when {
            // Off-screen to the left
            position < -1f -> {
                page.alpha = 0f
            }

            // Active front card swiping off-screen
            position <= 0f -> {
                page.alpha = 1f + position
                page.translationX = 0f
                page.translationY = 0f
                page.scaleX = 1f
                page.scaleY = 1f
                page.elevation = 10f
            }

            // Cards stacked behind the active card
            position <= maxVisibleItems -> {
                page.alpha = 1f

                // Cancel default horizontal displacement to keep cards stacked in place
                page.translationX = -page.width * position

                // Offset card vertically to show stacked edges
                page.translationY = -verticalOffsetDp * position

                // Shrink card size progressively for depth
                val scale = 1f - (scaleOffset * position)
                page.scaleX = scale
                page.scaleY = scale

                // Layer shadow elevation
                page.elevation = 10f - position
            }

            // Beyond max depth limit
            else -> {
                page.alpha = 0f
            }
        }
    }
}