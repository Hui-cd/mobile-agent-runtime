package ai.mobileagent.runtime

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject

/** Persist owned IDs, never inspect or install another app's downloads. */
class AppDownloads(private val context: Context) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val owned = context.getSharedPreferences("app-downloads", Context.MODE_PRIVATE)
    fun download(params: JSONObject): JSONObject {
        val uri = Uri.parse(params.getString("url"))
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "DOWNLOAD_REQUIRES_HTTPS" }
        val name = "app-${java.util.UUID.randomUUID()}.apk"
        val id = manager.enqueue(DownloadManager.Request(uri)
            .setTitle("Mobile Agent 应用下载")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name))
        owned.edit().putString(id.toString(), name).apply()
        return status(id)
    }
    fun file(id: Long): java.io.File {
        val name = owned.getString(id.toString(), null) ?: error("DOWNLOAD_NOT_OWNED")
        return java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
    }
    fun status(id: Long): JSONObject {
        file(id)
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            check(cursor.moveToFirst()) { "DOWNLOAD_NOT_FOUND" }
            fun long(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            val status = long(DownloadManager.COLUMN_STATUS).toInt()
            return JSONObject().put("download_id", id).put("status", when(status) {
                DownloadManager.STATUS_SUCCESSFUL -> "downloaded"
                DownloadManager.STATUS_FAILED -> "failed"
                DownloadManager.STATUS_PAUSED -> "paused"
                DownloadManager.STATUS_RUNNING -> "downloading"
                else -> "pending"
            }).put("bytes_downloaded", long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                .put("bytes_total", long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                .put("reason", long(DownloadManager.COLUMN_REASON)).put("installed", false)
        }
    }
}
