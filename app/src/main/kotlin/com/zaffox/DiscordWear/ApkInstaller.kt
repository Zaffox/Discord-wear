package com.zaffox.discordwear

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object ApkInstaller {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads the APK from [url] into the app's external files dir and
     * fires an Intent to install it (works on WearOS devices with a file manager
     * that supports APK installation). Returns the saved [File] on success.
     */
    suspend fun downloadAndInstall(context: Context, url: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir  = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val file = File(dir, "DiscordWear-update.apk")

            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val bytes = resp.body?.bytes() ?: error("Empty body")
                file.writeBytes(bytes)
            }

            // Fire install intent via FileProvider so Android 7+ can open it
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(install)

            file
        }
    }

    /** Opens the release HTML page in the phone's browser via RemoteIntent. */
    fun openInPhoneBrowser(context: Context, url: String) {
        runCatching {
            // RemoteIntent routes the browser intent to the paired phone
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            // Try RemoteIntent (Wear API) first, fall back to direct startActivity
            val remoteIntentClass = runCatching {
                Class.forName("com.google.android.wearable.intent.RemoteIntent")
            }.getOrNull()

            if (remoteIntentClass != null) {
                val method = remoteIntentClass.getMethod(
                    "startRemoteActivity", Context::class.java, Intent::class.java,
                    android.app.PendingIntent::class.java
                )
                method.invoke(null, context, intent, null)
            } else {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
