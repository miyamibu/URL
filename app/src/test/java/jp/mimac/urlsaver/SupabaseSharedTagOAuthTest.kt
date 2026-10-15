package jp.mimac.urlsaver

import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import jp.mimac.urlsaver.data.SharedTagAuthSession
import jp.mimac.urlsaver.data.SharedTagAuthRemoteResult
import jp.mimac.urlsaver.data.SharedTagOAuthPendingState
import jp.mimac.urlsaver.data.SharedTagOAuthStateStore
import jp.mimac.urlsaver.data.SharedTagSyncRemoteConfig
import jp.mimac.urlsaver.data.SupabaseSharedTagAuthRemoteDataSource
import jp.mimac.urlsaver.domain.LinkedIdentityProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseSharedTagOAuthTest {
    @Test
    fun linkedIdentityStatus_usesAuthenticatedUserGetAndFiltersSupportedProviders() = runBlocking {
        withAuthStub(listOf(
            200 to """{"id":"original-user","identities":[{"provider":"google"},{"provider":"apple"},{"provider":"email"}]}""",
        )) { baseURL, requests ->
            val session = SharedTagAuthSession("original-user", "test-access")
            val snapshot = remote(OAuthTestStateStore(), baseURL).fetchLinkedIdentityProviders(session)

            assertEquals("original-user", snapshot.authUserId)
            assertEquals(setOf(LinkedIdentityProvider.GOOGLE, LinkedIdentityProvider.APPLE), snapshot.providers)
            assertTrue(snapshot.verifiedAtEpochMillis > 0L)
            assertEquals("GET /auth/v1/user HTTP/1.1", requests.single().first)
            assertEquals("Bearer test-access", requests.single().second["authorization"])
            assertEquals("test-only", requests.single().second["apikey"])
            assertFalse(requests.single().second.containsKey("content-length"))
        }
    }

    @Test
    fun linkedIdentityStatus_rejectsWrongUserAndMissingIdentityList() = runBlocking {
        withAuthStub(listOf(
            200 to """{"id":"different-user","identities":[{"provider":"google"}]}""",
        )) { baseURL, _ ->
            val session = SharedTagAuthSession("original-user", "test-access")
            assertTrue(runCatching {
                remote(OAuthTestStateStore(), baseURL).fetchLinkedIdentityProviders(session)
            }.isFailure)
        }
        withAuthStub(listOf(
            200 to """{"id":"original-user"}""",
        )) { baseURL, _ ->
            val session = SharedTagAuthSession("original-user", "test-access")
            assertTrue(runCatching {
                remote(OAuthTestStateStore(), baseURL).fetchLinkedIdentityProviders(session)
            }.isFailure)
        }
    }

    @Test
    fun appleAuthorization_usesS256ChallengeAndExchangesCodeWithSavedVerifier() = runBlocking {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val state = OAuthTestStateStore()
        var requestPath: String? = null
        var requestBody: String? = null
        val worker = thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                requestPath = reader.readLine().split(' ')[1]
                var contentLength = 0
                while (true) {
                    val header = reader.readLine()
                    if (header.isNullOrEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(':').trim().toInt()
                    }
                }
                val body = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(body, read, contentLength - read)
                    check(count > 0)
                    read += count
                }
                requestBody = String(body)
                val response = """{"access_token":"test-access","refresh_token":"test-refresh","user":{"id":"apple-user","email":"apple@example.com"}}""".toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(response)
                    flush()
                }
            }
        }
        try {
            val remote = remote(state, "http://127.0.0.1:${server.localPort}")
            val url = remote.oauthUrl("apple", CALLBACK)
            val params = URI(url).rawQuery.split('&').associate { part ->
                val pieces = part.split('=', limit = 2)
                pieces[0] to URLDecoder.decode(pieces[1], "UTF-8")
            }
            val pending = requireNotNull(state.value)
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(pending.codeVerifier.toByteArray()),
            )
            assertEquals("apple", params["provider"])
            assertEquals(CALLBACK, params["redirect_to"])
            assertEquals("S256", params["code_challenge_method"])
            assertEquals(challenge, params["code_challenge"])
            assertFalse(url.contains(pending.codeVerifier))
            val result = remote.signInWithOAuthCallback("$CALLBACK?code=apple%2Bcode") as SharedTagAuthRemoteResult.SignedIn
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertEquals("apple-user", result.session.authUserId)
            assertEquals("/auth/v1/token?grant_type=pkce", requestPath)
            val body = Json.parseToJsonElement(requireNotNull(requestBody)).jsonObject
            assertEquals("apple+code", body["auth_code"]?.jsonPrimitive?.content)
            assertEquals(pending.codeVerifier, body["code_verifier"]?.jsonPrimitive?.content)
            assertNull(state.value)
        } finally {
            server.close()
        }
    }

    @Test
    fun callbacks_rejectWrongDestinationAndImplicitTokensWithoutNetwork() = runBlocking {
        val state = OAuthTestStateStore()
        val remote = remote(state)
        remote.oauthUrl("apple", CALLBACK)
        assertTrue(failure { remote.signInWithOAuthCallback("https://example.com/callback?code=test") }.contains("callback URL"))
        assertTrue(failure { remote.signInWithOAuthCallback("$CALLBACK#access_token=test&refresh_token=test") }.contains("PKCE"))
        assertNull(state.value)
    }

    @Test
    fun callbacks_reportProviderErrorAndRejectMissingOrExpiredStartWithoutNetwork() = runBlocking {
        val state = OAuthTestStateStore()
        val remote = remote(state)
        assertEquals("access denied", failure { remote.signInWithOAuthCallback("$CALLBACK?error_description=access%20denied") })
        assertTrue(failure { remote.signInWithOAuthCallback("$CALLBACK?code=test") }.contains("開始情報"))
        remote.oauthUrl("apple", CALLBACK)
        state.value = requireNotNull(state.value).copy(createdAtMillis = 0L)
        assertTrue(failure { remote.signInWithOAuthCallback("$CALLBACK?code=test") }.contains("有効期限"))
        assertNull(state.value)
    }

    @Test
    fun identityLink_authenticatedGetUsesPkceAndSameUserCallback() = runBlocking {
        withAuthStub(listOf(
            200 to """{"url":"https://provider.example.com/authorize?flow=link"}""",
            200 to """{"access_token":"linked-test","refresh_token":"refresh-test","user":{"id":"original-user","email":"owner@example.com"}}""",
        )) { baseURL, requests ->
            val state = OAuthTestStateStore()
            val remote = remote(state, baseURL)
            val session = SharedTagAuthSession("original-user", "test-link-access")
            val url = remote.linkIdentityOAuthUrl("google", session, CALLBACK)
            assertFalse(url.contains(session.accessToken))
            val pending = requireNotNull(state.value)
            assertEquals("original-user", pending.expectedAuthUserId)
            // Recreating the data source must retain the original account binding.
            val recreated = remote(state, baseURL)
            assertEquals("original-user", recreated.pendingIdentityLinkUserId())
            val result = recreated.signInWithOAuthCallback("$CALLBACK?code=link-code") as SharedTagAuthRemoteResult.SignedIn
            assertEquals("original-user", result.session.authUserId)
            val authorize = requests[0]
            assertTrue(authorize.first.startsWith("GET /auth/v1/user/identities/authorize?"))
            assertTrue(authorize.first.contains("provider=google"))
            assertTrue(authorize.first.contains("code_challenge_method=S256"))
            assertTrue(authorize.first.contains("code_challenge=${pending.codeChallenge}"))
            assertTrue(authorize.first.contains("skip_http_redirect=true"))
            assertFalse(authorize.first.contains(session.accessToken))
            assertEquals("Bearer test-link-access", authorize.second["authorization"])
            assertFalse(requests[1].second.containsKey("authorization"))
            assertNull(state.value)
        }
    }

    @Test
    fun identityLink_appleWrongUserAndDisabledManualLinkingNeverReturnSignedIn() = runBlocking {
        withAuthStub(listOf(
            200 to """{"url":"https://provider.example.com/apple"}""",
            200 to """{"access_token":"wrong-test","refresh_token":"wrong-refresh","user":{"id":"different-user"}}""",
            400 to """{"message":"Manual linking disabled"}""",
        )) { baseURL, requests ->
            val state = OAuthTestStateStore()
            val remote = remote(state, baseURL)
            val session = SharedTagAuthSession("original-user", "test-link-access")
            remote.linkIdentityOAuthUrl("apple", session, CALLBACK)
            assertTrue(failure { remote.signInWithOAuthCallback("$CALLBACK?code=wrong") }.contains("元のアカウント"))
            assertNull(state.value)
            assertTrue(failure { remote.linkIdentityOAuthUrl("apple", session, CALLBACK) }.contains("Manual linking disabled"))
            assertNull(state.value)
            assertTrue(requests.first().first.contains("provider=apple"))
        }
    }

    @Test
    fun identityLink_cancelAndProviderErrorClearOnlyPendingStateAndPreventOverwrite() = runBlocking {
        val state = OAuthTestStateStore()
        val remote = remote(state)
        state.value = SharedTagOAuthPendingState.create(CALLBACK).copy(expectedAuthUserId = "original-user")
        assertTrue(runCatching { remote.oauthUrl("google", CALLBACK) }.isFailure)
        assertEquals("original-user", remote.pendingIdentityLinkUserId())
        assertTrue(runCatching { remote.linkIdentityOAuthUrl("chatgpt", SharedTagAuthSession("original-user", "test"), CALLBACK) }.isFailure)
        assertEquals("access denied", failure { remote.signInWithOAuthCallback("$CALLBACK?error_description=access%20denied") })
        assertNull(state.value)
        state.value = SharedTagOAuthPendingState.create(CALLBACK).copy(expectedAuthUserId = "original-user")
        remote.cancelIdentityLink()
        assertNull(state.value)
        assertTrue(failure { remote.signInWithOAuthCallback("$CALLBACK?code=late") }.contains("開始情報"))
    }

    private suspend fun withAuthStub(
        responses: List<Pair<Int, String>>,
        block: suspend (String, MutableList<Pair<String, Map<String, String>>>) -> Unit,
    ) {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val worker = thread {
            try {
                responses.forEach { (status, response) ->
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val requestLine = reader.readLine()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val header = reader.readLine()
                            if (header.isNullOrEmpty()) break
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                        val count = headers["content-length"]?.toInt() ?: 0
                        repeat(count) { reader.read() }
                        requests += requestLine to headers
                        val bytes = response.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status Stub\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (_: java.net.SocketException) {
                // Fixture shutdown closes accept when a test fails before issuing all requests.
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}", requests)
            worker.join(5_000)
            assertFalse(worker.isAlive)
        } finally {
            server.close()
        }
    }

    private fun remote(state: OAuthTestStateStore, url: String = "http://127.0.0.1:1") =
        SupabaseSharedTagAuthRemoteDataSource(
            SharedTagSyncRemoteConfig(enabled = true, supabaseUrl = url, anonKey = "test-only"),
            state,
        )

    private suspend fun failure(block: suspend () -> Unit): String {
        try {
            block()
            throw AssertionError("Expected callback rejection")
        } catch (error: IOException) {
            return error.message.orEmpty()
        }
    }

    private companion object {
        const val CALLBACK = "urlsaver://auth/callback"
    }
}

private class OAuthTestStateStore : SharedTagOAuthStateStore {
    var value: SharedTagOAuthPendingState? = null
    override fun save(state: SharedTagOAuthPendingState) { value = state }
    override fun load(): SharedTagOAuthPendingState? = value
    override fun clear() { value = null }
}
