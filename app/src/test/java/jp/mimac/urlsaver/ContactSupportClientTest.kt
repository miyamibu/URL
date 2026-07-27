package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.ConfiguredContactSupportClient
import jp.mimac.urlsaver.data.ContactSupportRequest
import jp.mimac.urlsaver.data.ContactSupportResult
import jp.mimac.urlsaver.data.SharedTagAuthSession
import jp.mimac.urlsaver.data.SharedTagAuthSessionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSupportClientTest {
    @Test
    fun send_successPostsJsonAndReturnsSuccess() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"requestId":"11111111-1111-4111-8111-111111111111","status":"accepted"}"""))
        val result = runBlocking { client(server).send(validRequest()) }

        assertEquals(ContactSupportResult.Success("11111111-1111-4111-8111-111111111111"), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("22222222-2222-4222-8222-222222222222", request.getHeader("Idempotency-Key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("hello"))
        assertTrue(!body.contains("isSignedIn"))
        assertTrue(!body.contains("authUserId"))
    }

    @Test
    fun send_legacySuccessResponseIsAcceptedDuringCutover() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"sent"}"""))
        val result = runBlocking { client(server).send(validRequest()) }

        assertEquals(ContactSupportResult.Success(null), result)
    }

    @Test
    fun send_malformedSuccessResponseIsRejected() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val result = runBlocking { client(server).send(validRequest()) }

        assertEquals(
            ContactSupportResult.Failure("問い合わせを送信できませんでした。時間をおいて再度お試しください。"),
            result,
        )
    }

    @Test
    fun send_authenticatedSessionAddsBearerHeader() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"requestId":"33333333-3333-4333-8333-333333333333","status":"accepted"}"""))
        val provider = object : SharedTagAuthSessionProvider {
            private val sessionState = MutableStateFlow<SharedTagAuthSession?>(
                SharedTagAuthSession(
                    authUserId = "44444444-4444-4444-8444-444444444444",
                    accessToken = "test-access-token",
                ),
            )
            override val session: StateFlow<SharedTagAuthSession?> = sessionState

            override fun updateSession(newSession: SharedTagAuthSession?) {
                sessionState.value = newSession
            }
        }

        val result = runBlocking {
            ConfiguredContactSupportClient(
                endpointUrl = server.url("/contact-support").toString(),
                authSessionProvider = provider,
            ).send(validRequest())
        }

        assertEquals(ContactSupportResult.Success("33333333-3333-4333-8333-333333333333"), result)
        assertEquals("Bearer test-access-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun send_badRequestReturnsServerMessage() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"入力内容を確認してください"}"""))
        val result = runBlocking { client(server).send(validRequest()) }

        assertEquals(ContactSupportResult.Failure("入力内容を確認してください"), result)
    }

    @Test
    fun send_rateLimitedReturnsServerMessage() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"短時間に問い合わせが多すぎます"}"""))
        val result = runBlocking { client(server).send(validRequest()) }

        assertEquals(ContactSupportResult.Failure("短時間に問い合わせが多すぎます。少し時間をおいて再度お試しください。"), result)
    }

    @Test
    fun send_rateLimitedCodeReturnsJapaneseMessage() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate_limited"}"""))
        val result = runBlocking { client(server).send(validRequest()) }

        assertEquals(ContactSupportResult.Failure("短時間に問い合わせが多すぎます。少し時間をおいて再度お試しください。"), result)
    }

    @Test
    fun send_resendFailureReturnsServerMessage() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"Resend failed"}"""))
        val result = runBlocking { client(server).send(validRequest()) }

        assertEquals(ContactSupportResult.Failure("問い合わせを送信できませんでした。時間をおいて再度お試しください。"), result)
    }

    @Test
    fun send_blankEndpointFailsWithoutNetwork() = runBlocking {
        val result = ConfiguredContactSupportClient("").send(validRequest())

        assertEquals(ContactSupportResult.Failure("問い合わせ送信先が設定されていません"), result)
    }

    private fun client(server: MockWebServer): ConfiguredContactSupportClient {
        return ConfiguredContactSupportClient(server.url("/contact-support").toString())
    }

    private fun validRequest(): ContactSupportRequest {
        return ContactSupportRequest(
            email = "user@example.com",
            name = "User",
            message = "hello",
            platform = "android",
            appVersion = "1.0.11",
            buildType = "debug",
            isSignedIn = false,
            idempotencyKey = "22222222-2222-4222-8222-222222222222",
        )
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }
}
