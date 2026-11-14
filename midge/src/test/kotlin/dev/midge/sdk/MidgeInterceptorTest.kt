package dev.midge.sdk

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MidgeInterceptorTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().addInterceptor(MidgeInterceptor()).build()

    @Before fun start() {
        EventLog.clear()
        server.start()
    }

    @After fun stop() = server.shutdown()

    @Test fun recordsTheRequestAndLeavesTheResponseReadable() {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"error":"no card"}"""))
        val request = Request.Builder().url(server.url("/checkout")).header("Authorization", "Bearer x")
            .post("""{"card":"4242"}""".toRequestBody("application/json".toMediaType())).build()
        val body = client.newCall(request).execute().use { it.body!!.string() }
        assertEquals("""{"error":"no card"}""", body)  // the app still reads its response

        val event = EventLog.between(0.0, EventLog.now() + 1).single()
        assertEquals("network", event.getString("kind"))
        assertEquals("POST", event.getString("method"))
        assertEquals(404, event.getInt("status"))
        assertEquals("""{"card":"4242"}""", event.getString("requestBody"))
        assertEquals("""{"error":"no card"}""", event.getString("responseBody"))
        assertEquals("authorization", event.getJSONArray("requestHeaders").getJSONArray(0).getString(0))
    }

    @Test fun keepsBinaryBodiesOut() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("PNG…"))
        client.newCall(Request.Builder().url(server.url("/logo.png")).build()).execute().close()
        assertFalse(EventLog.between(0.0, EventLog.now() + 1).single().has("responseBody"))
    }

    @Test fun recordsAFailedConnection() {
        val url = server.url("/gone")
        server.shutdown()
        runCatching { client.newCall(Request.Builder().url(url).build()).execute() }
        val event = EventLog.between(0.0, EventLog.now() + 1).single()
        assertEquals(0, event.getInt("status"))
        assertTrue(event.getString("error").isNotEmpty())
    }

    @Test fun textTypes() {
        assertTrue(MidgeInterceptor.isText("application/problem+json".toMediaType()))
        assertTrue(MidgeInterceptor.isText("text/html; charset=utf-8".toMediaType()))
        assertFalse(MidgeInterceptor.isText("application/octet-stream".toMediaType()))
    }
}
