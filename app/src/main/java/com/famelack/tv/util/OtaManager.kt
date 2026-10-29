package com.famelack.tv.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val changelog: String,
    val sizeBytes: Long
)

class OtaManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun getCurrentVersion(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).versionName ?: "1.0.0"
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            }
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val currentVer = getCurrentVersion().removePrefix("v").removePrefix("V").trim()
        val request = Request.Builder()
            .url("https://api.github.com/repos/Kapy2003/Kapyo-Tv/releases/latest")
            .header("User-Agent", "KapyoTv/$currentVer")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "Update check response status: ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val rawTag = json.optString("tag_name", "").trim()
            val remoteVer = rawTag.removePrefix("v").removePrefix("V").trim()
            if (remoteVer.isEmpty()) return@withContext null

            if (!isNewer(remoteVer, currentVer)) {
                Log.d(TAG, "Current version ($currentVer) is up to date with remote ($remoteVer)")
                return@withContext null
            }

            val changelog = json.optString("body", "Performance improvements and bug fixes.").trim()
            val assets = json.optJSONArray("assets") ?: return@withContext null

            var apkUrl: String? = null
            var apkSize = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }

            if (apkUrl.isNullOrEmpty()) {
                Log.w(TAG, "Release $remoteVer found, but no .apk asset was present")
                return@withContext null
            }

            UpdateInfo(
                versionName = remoteVer,
                downloadUrl = apkUrl,
                changelog = changelog,
                sizeBytes = apkSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed checking for OTA updates", e)
            null
        }
    }

    suspend fun downloadApk(
        updateInfo: UpdateInfo,
        onProgress: (percent: Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Clean any older downloaded APKs to conserve TV storage
        updateDir.listFiles()?.forEach { if (it.isFile) it.delete() }

        val targetFile = File(updateDir, "KapyoTv_v${updateInfo.versionName}.apk")

        val request = Request.Builder()
            .url(updateInfo.downloadUrl)
            .header("User-Agent", "KapyoTv/${getCurrentVersion()}")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val responseBody = response.body ?: return@withContext null
            val totalBytes = if (updateInfo.sizeBytes > 0) updateInfo.sizeBytes else responseBody.contentLength()

            responseBody.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                    output.flush()
                }
            }

            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download update APK", e)
            if (targetFile.exists()) targetFile.delete()
            null
        }
    }

    fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "OtaManager"

        /**
         * Compares two semantic version strings (e.g. "1.2.0" vs "1.1.0").
         * Returns true if remote is strictly greater than current.
         */
        fun isNewer(remote: String, current: String): Boolean {
            val rParts = remote.removePrefix("v").removePrefix("V").split('.')
                .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val cParts = current.removePrefix("v").removePrefix("V").split('.')
                .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }
}

