package com.example.note2snap.adapter

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import coil.load
import com.example.note2snap.R
import com.google.android.material.card.MaterialCardView

class GalleryPhotoAdapter(
    private val context: Context,
    private val photos: List<Uri>,
    private val selectionIndex:
        (Uri) -> Int
) : BaseAdapter() {

    private data class Holder(
        val card: MaterialCardView,
        val image: ImageView,
        val overlay: View,
        val badge: TextView
    )

    override fun getCount():
        Int =
        photos.size

    override fun getItem(
        position: Int
    ): Uri =
        photos[
            position
        ]

    override fun getItemId(
        position: Int
    ): Long =
        position.toLong()

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val view:
            View

        val holder:
            Holder

        if (
            convertView ==
            null
        ) {
            view =
                LayoutInflater
                    .from(
                        context
                    )
                    .inflate(
                        R.layout.item_gallery_photo,
                        parent,
                        false
                    )

            holder =
                Holder(
                    card =
                        view.findViewById(
                            R.id.cardGalleryPhoto
                        ),
                    image =
                        view.findViewById(
                            R.id.ivGalleryPhoto
                        ),
                    overlay =
                        view.findViewById(
                            R.id.gallerySelectedOverlay
                        ),
                    badge =
                        view.findViewById(
                            R.id.tvGallerySelectionNumber
                        )
                )

            view.tag =
                holder

        } else {
            view =
                convertView

            holder =
                view.tag as
                    Holder
        }

        val uri =
            photos[
                position
            ]

        val selectedNumber =
            selectionIndex(
                uri
            )

        holder.image.load(
            uri
        ) {
            crossfade(
                120
            )

            size(
                420
            )
        }

        val selected =
            selectedNumber >
                0

        holder.card.strokeWidth =
            dp(
                if (
                    selected
                ) {
                    3
                } else {
                    1
                }
            )

        holder.card.setStrokeColor(
            context.getColor(
                if (
                    selected
                ) {
                    R.color.nts_blue
                } else {
                    R.color.nts_blue_line
                }
            )
        )

        holder.overlay.visibility =
            if (
                selected
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        holder.badge.visibility =
            if (
                selected
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        holder.badge.text =
            selectedNumber
                .toString()

        holder.image.alpha =
            if (
                selected
            ) {
                0.88f
            } else {
                1f
            }

        return view
    }

    private fun dp(
        value: Int
    ): Int =
        (
            value *
            context.resources
                .displayMetrics
                .density
            ).toInt()
}
