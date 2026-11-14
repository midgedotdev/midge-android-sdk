package dev.midge.sdk

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class RecordingLinkClientTest {
    private val server = MockWebServer()
    private lateinit var client: RecordingLinkClient

    @Before fun start() {
        server.start()
        client = RecordingLinkClient(server.url("/").toString(), "rl_test", "com.acme.shop")
    }

    @After fun stop() = server.shutdown()

    @Test fun readsTheLinkAndSaysWhichAppAsks() {
        server.enqueue(MockResponse().setBody("""{"title":"Bugs","workspace":"Acme","max_duration_ms":600000,"max_bytes":1,"site_verified":true,"redaction":{"fields":["ssn"],"headers":["x-key"]}}"""))
        val info = client.info()
        assertEquals("Acme", info.workspace)
        assertEquals(600_000L, info.maxDurationMs)
        assertEquals(true, info.siteVerified)
        assertEquals(listOf("ssn"), info.redaction!!.fields)
        val request = server.takeRequest()
        assertEquals("/api/record/rl_test/", request.path)
        assertEquals("com.acme.shop", request.getHeader("X-Midge-App"))
    }

    @Test fun submitsUploadsInChunksAndFinishes() {
        val uploads = server.url("/api/uploads/")
        server.enqueue(MockResponse().setBody("""{"id":"R1","upload":{"url":"${uploads}v","chunk_bytes":4},"uploads":{"video":{"url":"${uploads}v","chunk_bytes":4}},"finish":"claim"}"""))
        server.enqueue(MockResponse().setHeader("Upload-Offset", "4"))
        server.enqueue(MockResponse().setHeader("Upload-Offset", "6"))
        server.enqueue(MockResponse().setBody("{}"))
        val started = client.submit(JSONObject().put("size", 6))
        val file = File.createTempFile("midge", ".mp4").apply { writeText("abcdef"); deleteOnExit() }
        val seen = mutableListOf<Long>()
        client.upload(file, started.uploads.getValue("video")) { seen += it }
        client.finish(started.finish)

        assertEquals(listOf(4L, 6L), seen)
        assertEquals("/api/record/rl_test/submit/", server.takeRequest().path)
        val first = server.takeRequest()
        assertEquals("PATCH", first.method)
        assertEquals("0", first.getHeader("Upload-Offset"))
        assertEquals("abcd", first.body.readUtf8())
        assertEquals("ef", server.takeRequest().body.readUtf8())
        val finish = server.takeRequest()
        assertEquals("/api/record/rl_test/finish/", finish.path)
        assertTrue(finish.body.readUtf8().contains("claim"))
    }

    @Test fun passesOnMidgesReason() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"This app isn’t registered with Acme."}"""))
        try {
            client.submit(JSONObject())
            fail()
        } catch (error: RecordingLinkClient.Failure) {
            assertEquals("This app isn’t registered with Acme.", error.message)
        }
    }
}
