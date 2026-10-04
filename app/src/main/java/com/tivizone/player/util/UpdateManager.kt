package com.tivizone.player.util

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.tivizone.player.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String? = null
)

object UpdateManager {

    private const val UPDATE_CHECK_URL = "https://iptvproxy-x8rs.onrender.com/api/update/check"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val gson = Gson()

    @Volatile
    private var isChecking = false

    fun checkForUpdates(activity: Activity, showToastIfUpToDate: Boolean = false) {
        if (isChecking) return
        isChecking = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url(UPDATE_CHECK_URL)
                    .get()
                    .build()

                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) {
                    resp.close()
                    withContext(Dispatchers.Main) {
                        isChecking = false
                        if (showToastIfUpToDate && !activity.isFinishing) {
                            Toast.makeText(activity, "Kein Update-Server erreichbar", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }

                val body = resp.body?.string()
                resp.close()

                if (body.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) { isChecking = false }
                    return@launch
                }

                val info = gson.fromJson(body, UpdateInfo::class.java)
                val currentVersion = BuildConfig.VERSION_CODE

                withContext(Dispatchers.Main) {
                    isChecking = false
                    if (activity.isFinishing || activity.isDestroyed) return@withContext

                    if (info.versionCode > currentVersion) {
                        showUpdateDialog(activity, info)
                    } else if (showToastIfUpToDate) {
                        Toast.makeText(activity, "TiviZone ist auf dem neuesten Stand (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("UpdateManager", "Fehler beim Update-Check: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    isChecking = false
                    if (showToastIfUpToDate && !activity.isFinishing) {
                        Toast.makeText(activity, "Update-Prüfung fehlgeschlagen: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        val notes = if (!info.releaseNotes.isNullOrEmpty()) {
            "\n\nÄnderungen:\n${info.releaseNotes}"
        } else ""

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Neues Update verfügbar: v${info.versionName}")
            .setMessage("Eine neue Version von TiviZone steht zur Verfügung (aktuell: v${BuildConfig.VERSION_NAME}).$notes")
            .setPositiveButton("Jetzt aktualisieren") { _, _ ->
                startDownload(activity, info.apkUrl, info.versionName)
            }
            .setNegativeButton("Später", null)
            .setCancelable(true)
            .create()

        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun startDownload(activity: Activity, downloadUrl: String, versionName: String) {
        val progress = ProgressDialog(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert).apply {
            setTitle("Update wird geladen…")
            setMessage("Lade Version $versionName herunter. Bitte warten…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder()
                    .url(downloadUrl)
                    .get()
                    .build()

                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) {
                    throw Exception("Download fehlgeschlagen (HTTP ${resp.code})")
                }

                val body = resp.body ?: throw Exception("Leere Antwort vom Update-Server")
                val totalLength = body.contentLength()
                val outputFile = File(activity.cacheDir, "update_v${versionName}.apk")
                if (outputFile.exists()) outputFile.delete()

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        var downloaded = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalLength > 0) {
                                val percent = ((downloaded * 100) / totalLength).toInt()
                                withContext(Dispatchers.Main) {
                                    if (progress.isShowing) {
                                        progress.progress = percent
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                withContext(Dispatchers.Main) {
                    if (progress.isShowing) progress.dismiss()
                    installApk(activity, outputFile)
                }

            } catch (e: Exception) {
                AppLogger.e("UpdateManager", "Download fehlgeschlagen: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (progress.isShowing) progress.dismiss()
                    if (!activity.isFinishing) {
                        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Download fehlgeschlagen")
                            .setMessage("Das Update konnte nicht geladen werden: ${e.message}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(activity, "Bitte erlaube TiviZone die Installation von Updates", Toast.LENGTH_LONG).show()
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(permissionIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            activity.startActivity(installIntent)

        } catch (e: Exception) {
            AppLogger.e("UpdateManager", "Fehler beim Starten der Installation: ${e.message}", e)
            Toast.makeText(activity, "Installation konnte nicht gestartet werden: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
