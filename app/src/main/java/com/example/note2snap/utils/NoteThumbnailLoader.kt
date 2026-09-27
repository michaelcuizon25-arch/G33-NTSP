package com.example.note2snap.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.collection.LruCache
import java.io.File
import java.util.concurrent.Executors

object NoteThumbnailLoader {

    private val cache =
        object : LruCache<String, Bitmap>(24) {}

    private val executor =
        Executors.newFixedThreadPool(2)

    fun load(
        imageView: ImageView,
        imagePath: String?,
        @DrawableRes fallbackRes: Int
    ) {
        if (imagePath.isNullOrBlank()) {
            showFallback(
                imageView,
                fallbackRes
            )
            return
        }

        val file =
            File(imagePath)

        if (!file.exists()) {
            showFallback(
                imageView,
                fallbackRes
            )
            return
        }

        imageView.tag =
            imagePath

        val cached =
            cache.get(imagePath)

        if (cached != null) {
            showBitmap(
                imageView,
                cached
            )
            return
        }

        // Show lightweight placeholder immediately.
        showFallback(
            imageView,
            fallbackRes
        )

        executor.execute {
            val bitmap =
                decodeSampledBitmap(
                    file.absolutePath,
                    220,
                    220
                )

            if (bitmap != null) {
                cache.put(
                    imagePath,
                    bitmap
                )

                imageView.post {
                    if (
                        imageView.tag ==
                        imagePath
                    ) {
                        showBitmap(
                            imageView,
                            bitmap
                        )
                    }
                }
            }
        }
    }

    private fun showBitmap(
        imageView: ImageView,
        bitmap: Bitmap
    ) {
        imageView.imageTintList = null
        imageView.clearColorFilter()
        imageView.setPadding(
            0,
            0,
            0,
            0
        )
        imageView.scaleType =
            ImageView.ScaleType.CENTER_CROP
        imageView.setImageBitmap(bitmap)
    }

    private fun showFallback(
        imageView: ImageView,
        @DrawableRes fallbackRes: Int
    ) {
        imageView.tag = null
        imageView.imageTintList = null
        imageView.clearColorFilter()
        imageView.setPadding(
            0,
            0,
            0,
            0
        )
        imageView.scaleType =
            ImageView.ScaleType.FIT_CENTER
        imageView.setImageResource(
            fallbackRes
        )
    }

    private fun decodeSampledBitmap(
        path: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {

        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

        BitmapFactory.decodeFile(
            path,
            bounds
        )

        val options =
            BitmapFactory.Options().apply {
                inSampleSize =
                    calculateInSampleSize(
                        bounds,
                        reqWidth,
                        reqHeight
                    )

                inJustDecodeBounds = false
                inPreferredConfig =
                    Bitmap.Config.RGB_565
            }

        return BitmapFactory.decodeFile(
            path,
            options
        )
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {

        val height =
            options.outHeight

        val width =
            options.outWidth

        var inSampleSize = 1

        if (
            height > reqHeight ||
            width > reqWidth
        ) {
            var halfHeight =
                height / 2

            var halfWidth =
                width / 2

            while (
                halfHeight / inSampleSize >=
                reqHeight &&
                halfWidth / inSampleSize >=
                reqWidth
            ) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }
}
