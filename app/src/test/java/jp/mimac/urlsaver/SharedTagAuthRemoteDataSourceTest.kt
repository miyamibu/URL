package jp.mimac.urlsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.SharedPreferencesSharedTagOAuthStateStore
import jp.mimac.urlsaver.data.SharedTagAuthRemoteResult
import jp.mimac.urlsaver.data.SharedTagOAuthPendingState
import jp.mimac.urlsaver.data.SharedTagOAuthStateStore
import jp.mimac.urlsaver.data.SharedTagOAuthStateProtector
import jp.mimac.urlsaver.data.SharedTagSyncRemoteConfig
import jp.mimac.urlsaver.data.SupabaseSharedTagAuthRemoteDataSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class SharedTagAuthRemoteDataSourceTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun invalidCallbacksRejectDuplicatesMixingAndNonExactBaseWithoutConsumingPending() {
        val callbacks = listOf(
            "urlsaver://auth/callback?code=one&code=two",
            "urlsaver://auth/callback?code=one#code=two",
            "urlsaver://auth/callback?code=one#",
            "urlsaver://auth/callback?code=one#other=value",
            "urlsaver://auth/callback?error=one&error=two",
            "urlsaver://auth/callback?code=one&error=denied",
            "urlsaver://auth/callback?code=",
            "urlsaver://auth/callback.evil?code=one",
            "urlsaver://auth:443/callback?code=one",
        )

        callbacks.forEach { callback ->
            val stateStore = RecordingOAuthStateStore()
            val dataSource = dataSource(stateStore)
            dataSource.oauthUrl(provider = "google", redirectTo = "urlsaver://auth/callback")

            assertThrows(IOException::class.java) {
                runBlocking { dataSource.signInWithOAuthCallback(callback) }
            }
            assertNotNull("pending state was consumed for $callback", stateStore.load())
            assertEquals(0, stateStore.clearCount)
        }
    }

    @Test
    fun tokenCallbackIsRejectedWithoutConsumingPending() {
        val stateStore = RecordingOAuthStateStore()
        val dataSource = dataSource(stateStore)
        dataSource.oauthUrl(provider = "google", redirectTo = "urlsaver://auth/callback")

        assertThrows(IOException::class.java) {
            runBlocking {
                dataSource.signInWithOAuthCallback(
                    "urlsaver://auth/callback#access_token=token&refresh_token=refresh"
                )
            }
        }

        assertNotNull(stateStore.load())
        assertEquals(0, stateStore.clearCount)
    }

    @Test
    fun successfulTokenExchangeConsumesPendingOnlyAfterSessionIsValid() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "access_token": "access-token",
                      "refresh_token": "refresh-token",
                      "user": { "id": "user-id", "email": "user@example.com" }
                    }
                    """.trimIndent()
                )
        )
        val stateStore = RecordingOAuthStateStore()
        val dataSource = dataSource(stateStore)
        dataSource.oauthUrl(provider = "google", redirectTo = "urlsaver://auth/callback")

        val result = runBlocking {
            dataSource.signInWithOAuthCallback(
                "urlsaver://auth/callback?code=authorization-code&provider=google",
            )
        }

        assertTrue(result is SharedTagAuthRemoteResult.SignedIn)
        assertNull(stateStore.load())
        assertEquals(1, stateStore.clearCount)
        val request = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=pkce", request.path)
        assertTrue(request.body.readUtf8().contains("authorization-code"))
    }

    @Test
    fun authorizeProviderIsFixedAndStoredInPendingState() {
        val stateStore = RecordingOAuthStateStore()
        val dataSource = dataSource(stateStore)

        val authorizeUrl = dataSource.oauthUrl(
            provider = "google",
            redirectTo = "urlsaver://auth/callback",
        )

        assertTrue(authorizeUrl.contains("provider=google"))
        assertEquals("google", stateStore.load()?.provider)
        assertThrows(IOException::class.java) {
            dataSource.oauthUrl(provider = "apple", redirectTo = "urlsaver://auth/callback")
        }
        assertEquals(1, stateStore.saveCount)
    }

    @Test
    fun callbackProviderMismatchIsRejectedWithoutConsumingPending() {
        val stateStore = RecordingOAuthStateStore()
        val dataSource = dataSource(stateStore)
        dataSource.oauthUrl(provider = "google", redirectTo = "urlsaver://auth/callback")

        assertThrows(IOException::class.java) {
            runBlocking {
                dataSource.signInWithOAuthCallback(
                    "urlsaver://auth/callback?code=authorization-code&provider=apple",
                )
            }
        }

        assertNotNull(stateStore.load())
        assertEquals(0, stateStore.clearCount)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun failedTokenExchangeKeepsPendingForRetry() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"invalid code\"}")
        )
        val stateStore = RecordingOAuthStateStore()
        val dataSource = dataSource(stateStore)
        dataSource.oauthUrl(provider = "google", redirectTo = "urlsaver://auth/callback")

        assertThrows(IOException::class.java) {
            runBlocking {
                dataSource.signInWithOAuthCallback("urlsaver://auth/callback?code=authorization-code")
            }
        }

        assertNotNull(stateStore.load())
        assertEquals(0, stateStore.clearCount)
    }

    @Test
    fun concurrentOAuthStartDoesNotReplaceExistingPendingState() {
        val stateStore = RecordingOAuthStateStore()
        val dataSource = dataSource(stateStore)
        val firstURL = dataSource.oauthUrl(provider = "google", redirectTo = "urlsaver://auth/callback")
        val firstPending = stateStore.load()

        assertThrows(IOException::class.java) {
            dataSource.oauthUrl(provider = "google", redirectTo = "urlsaver://auth/callback")
        }

        assertEquals(firstPending, stateStore.load())
        assertTrue(firstURL.contains("code_challenge="))
        assertEquals(1, stateStore.saveCount)
    }

    @Test
    fun consumeOnlyClearsTheExactUnexpiredPendingState() {
        val stateStore = RecordingOAuthStateStore()
        val pending = SharedTagOAuthPendingState(
            provider = "google",
            codeVerifier = "verifier",
            codeChallenge = "challenge",
            redirectTo = "urlsaver://auth/callback",
            createdAtMillis = System.currentTimeMillis(),
        )
        stateStore.save(pending)

        assertFalse(stateStore.consume(pending.copy(codeVerifier = "different-verifier")))
        assertEquals(pending, stateStore.load())
        assertTrue(stateStore.consume(pending))
        assertNull(stateStore.load())
    }

    @Test
    fun sharedPreferencesPendingStateIsStoredAsOneEncryptedCommittedBlob() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("shared_tag_oauth_state", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val state = SharedTagOAuthPendingState(
                provider = "google",
                codeVerifier = "verifier",
                codeChallenge = "challenge",
                redirectTo = "urlsaver://auth/callback",
                createdAtMillis = System.currentTimeMillis(),
            )
            val store = SharedPreferencesSharedTagOAuthStateStore(context, TestAesGcmProtector())

            store.save(state)

            assertEquals(1, preferences.all.size)
            val rawBlob = preferences.getString("pending_state", null)
            assertNotNull(rawBlob)
            assertTrue(rawBlob!!.startsWith("v1:"))
            assertFalse(rawBlob.contains("codeVerifier"))
            assertFalse(rawBlob.contains("verifier"))
            assertFalse(rawBlob.contains("google"))
            assertEquals(state, store.load())
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun legacyPlaintextBlobIsRejectedForCallbackAndReplacedOnNextStart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("shared_tag_oauth_state", Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putString(
                "pending_state",
                """
                {"codeVerifier":"legacy-verifier","codeChallenge":"legacy-challenge","redirectTo":"urlsaver://auth/callback","createdAtMillis":${System.currentTimeMillis()}}
                """.trimIndent(),
            )
            .commit()
        try {
            val store = SharedPreferencesSharedTagOAuthStateStore(context, TestAesGcmProtector())
            val legacy = requireNotNull(store.load())

            assertTrue(legacy.isExpired())
            assertFalse(store.consume(legacy))
            assertEquals(legacy, store.load())
            val dataSource = dataSource(store)
            assertThrows(IOException::class.java) {
                runBlocking {
                    dataSource.signInWithOAuthCallback("urlsaver://auth/callback?code=legacy-code")
                }
            }
            assertEquals(0, server.requestCount)

            val replacement = SharedTagOAuthPendingState(
                provider = "google",
                codeVerifier = "replacement-verifier",
                codeChallenge = "replacement-challenge",
                redirectTo = "urlsaver://auth/callback",
                createdAtMillis = System.currentTimeMillis(),
            )
            assertTrue(store.saveIfAbsent(replacement))
            assertEquals(replacement, store.load())
            assertTrue(preferences.getString("pending_state", "").orEmpty().startsWith("v1:"))
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun legacySplitPreferencesStateIsBoundToNoProviderAndCannotBeConsumed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("shared_tag_oauth_state", Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putString("code_verifier", "legacy-verifier")
            .putString("code_challenge", "legacy-challenge")
            .putString("redirect_to", "urlsaver://auth/callback")
            .putLong("created_at", System.currentTimeMillis())
            .commit()
        try {
            val store = SharedPreferencesSharedTagOAuthStateStore(context, TestAesGcmProtector())
            val legacy = requireNotNull(store.load())

            assertTrue(legacy.isExpired())
            assertFalse(store.consume(legacy))
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private fun dataSource(stateStore: SharedTagOAuthStateStore): SupabaseSharedTagAuthRemoteDataSource {
        return SupabaseSharedTagAuthRemoteDataSource(
            config = SharedTagSyncRemoteConfig(
                enabled = true,
                supabaseUrl = server.url("/").toString().trimEnd('/'),
                anonKey = "anon-key",
            ),
            oauthStateStore = stateStore,
        )
    }
}

private class RecordingOAuthStateStore : SharedTagOAuthStateStore {
    private var state: SharedTagOAuthPendingState? = null
    var saveCount: Int = 0
        private set
    var clearCount: Int = 0
        private set

    override fun save(state: SharedTagOAuthPendingState) {
        this.state = state
        saveCount += 1
    }

    override fun load(): SharedTagOAuthPendingState? = state

    override fun clear() {
        state = null
        clearCount += 1
    }
}

private class TestAesGcmProtector : SharedTagOAuthStateProtector {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return "v1:${encoder.encodeToString(iv)}:${encoder.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))}"
    }

    override fun decrypt(ciphertext: String): String {
        val parts = ciphertext.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == "v1")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, Base64.getUrlDecoder().decode(parts[1])),
        )
        return String(
            cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])),
            Charsets.UTF_8,
        )
    }
}
