package jp.mimac.urlsaver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_CREATED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_DUPLICATE_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_FAILED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_RESTORED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_TOTAL_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_DEGRADATION_NOTICE
import jp.mimac.urlsaver.data.EXTRA_SHARE_ENTRY_ID
import jp.mimac.urlsaver.data.EXTRA_MAIN_INTENT_EVENT_TOKEN
import jp.mimac.urlsaver.data.EXTRA_SHARE_SAVE_RESULT
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ShareExtractionResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.UrlRules
import kotlinx.coroutines.launch
import java.util.UUID

class ShareReceiverActivity : ComponentActivity() {

    private data class BatchSaveSummary(
        val total: Int,
        val created: Int,
        val duplicate: Int,
        val restored: Int,
        val failed: Int,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val repository = (application as UrlSaverApp).container.repository
            val nonUrlRedirect = ShareReceiverEntrypointRouter.resolve(
                activity = this@ShareReceiverActivity,
                sourceIntent = intent,
                tagRepository = (application as UrlSaverApp).container.tagRepository,
            )
            if (nonUrlRedirect != null) {
                startActivity(nonUrlRedirect)
                finish()
                return@launch
            }
            val isSendMultiple = intent.action == Intent.ACTION_SEND_MULTIPLE
            var degradationNotice: String? = null
            var batchSummary: BatchSaveSummary? = null

            val saveResult = if (isSendMultiple) {
                val extractedBatch = UrlRules.extractAllFromIntent(intent)
                val extractedUrls = extractedBatch.urls
                when {
                    extractedUrls.isEmpty() -> {
                        when (val extracted = UrlRules.extractFromIntent(intent)) {
                            ShareExtractionResult.InputTooLarge -> SaveResult(ShareSaveResult.INPUT_TOO_LARGE)
                            ShareExtractionResult.InvalidUrl -> SaveResult(ShareSaveResult.INVALID_URL)
                            ShareExtractionResult.NoUrlFound -> SaveResult(ShareSaveResult.NO_URL_FOUND)
                            is ShareExtractionResult.Found -> repository.saveFromManualInput(extracted.url)
                        }
                    }
                    extractedUrls.size == 1 -> repository.saveFromManualInput(extractedUrls.first())
                    else -> {
                        if (extractedBatch.truncatedToMaxUrls) {
                            degradationNotice = SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS
                        }
                        batchSummary = saveBatch(repository, extractedUrls)
                        SaveResult(ShareSaveResult.BATCH_PROCESSED)
                    }
                }
            } else {
                if (UrlRules.countValidUrlsInIntent(intent) > 1) {
                    degradationNotice = SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL
                }
                repository.saveFromIntent(intent)
            }

            val mainIntent = buildMainRedirectIntent().apply {
                putExtra(EXTRA_SHARE_SAVE_RESULT, saveResult.result.name)
                degradationNotice?.let { putExtra(EXTRA_SHARE_DEGRADATION_NOTICE, it) }
                batchSummary?.let { summary ->
                    putExtra(EXTRA_SHARE_BATCH_TOTAL_COUNT, summary.total)
                    putExtra(EXTRA_SHARE_BATCH_CREATED_COUNT, summary.created)
                    putExtra(EXTRA_SHARE_BATCH_DUPLICATE_COUNT, summary.duplicate)
                    putExtra(EXTRA_SHARE_BATCH_RESTORED_COUNT, summary.restored)
                    putExtra(EXTRA_SHARE_BATCH_FAILED_COUNT, summary.failed)
                }
                when (saveResult.result) {
                    ShareSaveResult.BATCH_PROCESSED -> Unit
                    ShareSaveResult.RESTORED_FROM_PENDING_DELETE -> {
                        val restoredEntryId = saveResult.entryId
                        if (restoredEntryId != null) {
                            putExtra(EXTRA_SHARE_ENTRY_ID, restoredEntryId)
                        } else {
                            putExtra(EXTRA_SHARE_SAVE_RESULT, ShareSaveResult.SAVE_FAILED.name)
                        }
                    }
                    ShareSaveResult.DUPLICATE_ACTIVE,
                    ShareSaveResult.DUPLICATE_ARCHIVED,
                    -> {
                        saveResult.entryId?.let { putExtra(EXTRA_SHARE_ENTRY_ID, it) }
                    }
                    ShareSaveResult.INPUT_TOO_LARGE -> Unit
                    else -> Unit
                }
            }

            startActivity(mainIntent)
            finish()
        }
    }

    private fun buildMainRedirectIntent(): Intent {
        return Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN, UUID.randomUUID().toString())
        }
    }

    private suspend fun saveBatch(repository: UrlRepository, urls: List<String>): BatchSaveSummary {
        var created = 0
        var duplicate = 0
        var restored = 0
        var failed = 0

        for (url in urls) {
            when (repository.saveFromManualInput(url).result) {
                ShareSaveResult.CREATED -> created += 1
                ShareSaveResult.DUPLICATE_ACTIVE,
                ShareSaveResult.DUPLICATE_ARCHIVED,
                -> duplicate += 1
                ShareSaveResult.RESTORED_FROM_PENDING_DELETE -> restored += 1
                ShareSaveResult.SAVE_FAILED,
                ShareSaveResult.INPUT_TOO_LARGE,
                ShareSaveResult.INVALID_URL,
                ShareSaveResult.NO_URL_FOUND,
                ShareSaveResult.BATCH_PROCESSED,
                -> failed += 1
            }
        }

        return BatchSaveSummary(
            total = urls.size,
            created = created,
            duplicate = duplicate,
            restored = restored,
            failed = failed,
        )
    }
}
