package com.example.note2snap.activities

import com.example.note2snap.preprocessing.GuideCropGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class PreparedCameraPage(val path: String, val quality: ImageQualityResult)

/** Geometry was captured on main; both injected operations must be View-free. */
internal class CameraPagePreparer(
    private val crop: (File, GuideCropGeometry?) -> String?,
    private val validate: (String) -> ImageQualityResult
) {
    suspend fun prepare(file: File, geometry: GuideCropGeometry?): PreparedCameraPage = withContext(Dispatchers.IO) {
        val path = crop(file, geometry) ?: file.absolutePath
        PreparedCameraPage(path, validate(path))
    }
}
