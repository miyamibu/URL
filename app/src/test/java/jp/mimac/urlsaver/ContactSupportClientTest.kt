package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.ConfiguredContactSupportClient
import jp.mimac.urlsaver.data.ContactSupportRequest
import jp.mimac.urlsaver.data.ContactSupportResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSupportClientTest {
    @Test
    fun send_successPostsJsonAndReturnsSuccess() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"requestId":"req-1","status":"accepted"}"""))
        val result = runBlocking { client(server).send(validRequest()) }

        assertEquals(ContactSupportResult.Success("req-1"), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Idempotency-Key")?.isNotBlank() == true)
        assertTrue(request.body.readUtf8().contains("hello"))
    }

    @Test
    fun send_legacySuccessResponseReturnsSuccess() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"sent"}"""))
        val result = runBlocking { client(server).send(validRequest()) }

        assertEquals(ContactSupportResult.Success(""), result)
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
