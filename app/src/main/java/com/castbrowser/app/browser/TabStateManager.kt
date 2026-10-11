package com.castbrowser.app.browser

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import java.io.File
import java.io.FileOutputStream

class TabStateManager(private val context: Context) {
    private val tabsDir = File(context.filesDir, "tabs").apply {
        if (!exists()) mkdirs()
    }

    private val thumbsDir = File(context.filesDir, "thumbnails").apply {
        if (!exists()) mkdirs()
    }

    fun getStateFile(tabId: String): File = File(tabsDir, "$tabId.bin")

    fun getThumbnailFile(tabId: String): File = File(thumbsDir, "$tabId.webp")

    fun saveTabBundle(tabId: String, bundle: Bundle): Boolean {
        return try {
            val file = getStateFile(tabId)
            val parcel = Parcel.obtain()
            try {
                bundle.writeToParcel(parcel, 0)
                val bytes = parcel.marshall()
                FileOutputStream(file).use { it.write(bytes) }
                true
            } finally {
                parcel.recycle()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun restoreTabBundle(tabId: String): Bundle? {
        val file = getStateFile(tabId)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val bytes = file.readBytes()
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                Bundle.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteTabFiles(tabId: String) {
        try {
            getStateFile(tabId).delete()
            getThumbnailFile(tabId).delete()
        } catch (_: Exception) {}
    }

    fun clearAllFiles() {
        try {
            tabsDir.deleteRecursively()
            thumbsDir.deleteRecursively()
        } catch (_: Exception) {}
    }
}
