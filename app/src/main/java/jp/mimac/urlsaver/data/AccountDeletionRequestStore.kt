package jp.mimac.urlsaver.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable record of an in-flight remote account deletion. It is persisted BEFORE
 * the remote delete call so that a process loss after the server committed the
 * deletion can still converge through the status query instead of repeating or
 * stalling (S1-REMOTE-MARKER-006).
 */
@Serializable
data class AccountDeletionRequestRecord(
    val authUserId: String,
    val requestId: String,
    val token: String,
)

interface AccountDeletionRequestStore {
    val pending: AccountDeletionRequestRecord?

    fun save(record: AccountDeletionRequestRecord)

    fun clear()
}

class SharedPreferencesAccountDeletionRequestStore(
    context: Context,
) : AccountDeletionRequestStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val pending: AccountDeletionRequestRecord?
        get() = loadPending()

    override fun save(record: AccountDeletionRequestRecord) {
        val normalizedAuthUserId = record.authUserId.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("authUserId is required")
        val normalizedRequestId = record.requestId.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("requestId is required")
        val normalizedToken = record.token.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("token is required")
        val committed = prefs.edit()
            .putString(
                KEY_PENDING_REQUEST,
                json.encodeToString(
                    AccountDeletionRequestRecord(
                        authUserId = normalizedAuthUserId,
                        requestId = normalizedRequestId,
                        token = normalizedToken,
                    )
                ),
            )
            .commit()
        check(committed) { "Could not persist the account deletion request record" }
    }

    override fun clear() {
        val committed = prefs.edit()
            .remove(KEY_PENDING_REQUEST)
            .commit()
        check(committed) { "Could not clear the account deletion request record" }
    }

    private fun loadPending(): AccountDeletionRequestRecord? {
        val raw = prefs.getString(KEY_PENDING_REQUEST, null)?.takeIf { it.isNotBlank() }
            ?: return null
        // A corrupt blob cannot prove anything about the remote state. Report no
        // record and keep the blob untouched; deletion then falls back to the
        // fail-closed session-based paths.
        return runCatching {
            json.decodeFromString<AccountDeletionRequestRecord>(raw)
        }.getOrNull()?.takeIf { record ->
            record.authUserId.isNotBlank() && record.requestId.isNotBlank() && record.token.isNotBlank()
        }
    }

    private companion object {
        const val PREFS_NAME = "account_deletion_request"
        const val KEY_PENDING_REQUEST = "pending_request"
    }
}

object NoopAccountDeletionRequestStore : AccountDeletionRequestStore {
    private var record: AccountDeletionRequestRecord? = null

    override val pending: AccountDeletionRequestRecord?
        get() = record

    override fun save(record: AccountDeletionRequestRecord) {
        this.record = record
    }

    override fun clear() {
        record = null
    }
}
