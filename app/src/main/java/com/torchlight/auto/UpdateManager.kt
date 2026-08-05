package com.torchlight.auto

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object UpdateManager {
    private const val GITHUB_API = "https://api.github.com/repos/lyalnr/TorchlightAuto/releases/latest"
    private const val APK_NAME = "app-debug.apk"
    private val executor = Executors.newSingleThreadExecutor()
    private var downloadId: Long = -1

    fun checkUpdate(context: Context, callback: (String, Boolean, String?) -> Unit) {
        executor.execute {
            try {
                val url = URL(GITHUB_API)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

                if (conn.responseCode == 200) {
                    val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonText)
                    val latestVersion = json.optString("tag_name", "unknown")
                    val downloadUrl = json.optJSONArray("assets")?.optJSONObject(0)?.optString("browser_download_url")
                    val body = json.optString("body", "无更新说明")

                    val currentVersion = "v${context.packageManager.getPackageInfo(context.packageName, 0).versionName}"
                    val hasUpdate = latestVersion != currentVersion && latestVersion != "unknown"

                    callback.invoke("最新版本: $latestVersion\n当前: $currentVersion\n\n$body", hasUpdate, downloadUrl)
                } else {
                    callback.invoke("检查失败: HTTP ${conn.responseCode}", false, null)
                }
                conn.disconnect()
            } catch (e: Exception) {
                callback.invoke("检查失败: ${e.message}", false, null)
            }
        }
    }

    fun downloadAndInstall(context: Context, downloadUrl: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl))
            request.setTitle("日志监控更新")
            request.setDescription("正在下载最新版本...")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            request.setMimeType("application/vnd.android.package-archive")

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = dm.enqueue(request)

            // 注册下载完成监听
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        installApk(context!!)
                        context.unregisterReceiver(this)
                    }
                }
            }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))

            Toast.makeText(context, "开始下载更新...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun installApk(context: Context) {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
        if (!file.exists()) {
            Toast.makeText(context, "APK文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            Uri.fromFile(file)
        }

        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
