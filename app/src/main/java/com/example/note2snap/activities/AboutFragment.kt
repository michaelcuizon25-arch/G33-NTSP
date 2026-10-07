package com.example.note2snap.activities

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.note2snap.R

class AboutFragment : Fragment(R.layout.fragment_about) {

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        setBottomNavigationVisible(false)

        view.findViewById<View>(
            R.id.btnAboutBack
        ).setOnClickListener {
            parentFragmentManager
                .popBackStack()
        }

        val versionName =
            try {
                requireContext()
                    .packageManager
                    .getPackageInfo(
                        requireContext()
                            .packageName,
                        0
                    )
                    .versionName
                    ?: "1.0"
            } catch (_: Exception) {
                "1.0"
            }

        view.findViewById<TextView>(
            R.id.tvAboutVersion
        ).text =
            "Version $versionName"
    }

    override fun onDestroyView() {
        super.onDestroyView()

        if (
            parentFragmentManager
                .backStackEntryCount <= 1
        ) {
            setBottomNavigationVisible(
                true
            )
        }
    }

    private fun setBottomNavigationVisible(
        visible: Boolean
    ) {
        val state =
            if (visible) {
                View.VISIBLE
            } else {
                View.GONE
            }

        activity
            ?.findViewById<View>(
                R.id.bottomNavContainer
            )
            ?.visibility =
            state

        activity
            ?.findViewById<View>(
                R.id.scanFab
            )
            ?.visibility =
            state
    }
}
