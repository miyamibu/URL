package jp.mimac.urlsaver.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import jp.mimac.urlsaver.UrlSaverApp
import jp.mimac.urlsaver.data.MetadataWorkScheduler
import java.net.URI

class ResolveWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val entryId = inputData.getLong(MetadataWorkScheduler.KEY_ENTRY_ID, -1L)
        if (entryId <= 0L) return Result.success()

        val repository = (applicationContext as UrlSaverApp).container.repository
        val entry = repository.loadEntry(entryId) ?: return Result.success()
        val canonical = resolveCanonical(entry.normalizedUrl)
        repository.applyCanonicalId(entryId, canonical)
        return Result.success()
    }

    private fun resolveCanonical(normalizedUrl: String): String? {
        val uri = runCatching { URI(normalizedUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        return when {
            host.endsWith("youtube.com") -> uri.query
                ?.split('&')
                ?.firstOrNull { it.startsWith("v=") }
                ?.removePrefix("v=")
            host == "youtu.be" -> path.trim('/').ifBlank { null }
            host.endsWith("tiktok.com") -> path.substringAfterLast('/').ifBlank { null }
            host == "x.com" || host.endsWith("twitter.com") ->
                path.substringAfterLast("/status/", "").ifBlank { null }
            else -> null
        }
    }
}
