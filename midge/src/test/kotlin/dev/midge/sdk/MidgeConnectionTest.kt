package dev.midge.sdk

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URL

class MidgeConnectionTest {
    private val server = MockWebServer()

    @Before fun start() {
        EventLog.clear()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun events() = EventLog.between(0.0, EventLog.now() + 1)

    @Test fun recordsAPostAndLeavesTheResponseReadable() {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody("""{"id":7}"""))
        val c = Midge.openConnection(server.url("/orders").toUrl())
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer x")
        c.outputStream.use { it.write("""{"card":"4242"}""".toByteArray()) }
        assertEquals(201, c.responseCode)
        assertEquals("""{"id":7}""", c.inputStream.bufferedReader().use { it.readText() })

        val e = events().single()
        assertEquals("POST", e.getString("method"))
        assertEquals(201, e.getInt("status"))
        assertEquals("""{"card":"4242"}""", e.getString("requestBody"))
        assertEquals("""{"id":7}""", e.getString("responseBody"))
        val headers = e.getJSONArray("requestHeaders").toString()
        assertTrue(headers, headers.contains("\"authorization\""))
        assertEquals("application/json", server.takeRequest().getHeader("Content-Type"))  // the request went out unchanged
    }

    @Test fun anErrorBodyIsReadFromTheErrorStream() {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("no such cart"))
        val c = Midge.openConnection(server.url("/cart").toUrl())
        assertEquals(404, c.responseCode)
        assertEquals("no such cart", c.errorStream!!.bufferedReader().use { it.readText() })
        val e = events().single()
        assertEquals(404, e.getInt("status"))
        assertEquals("no such cart", e.getString("responseBody"))
    }

    @Test fun aFailedConnectionIsRecordedWithItsError() {
        val url = server.url("/gone").toUrl()
        server.shutdown()
        val c = Midge.openConnection(url)
        runCatching { c.responseCode }
        val e = events().single()
        assertEquals(0, e.getInt("status"))
        assertTrue(e.getString("error").isNotEmpty())
    }

    @Test fun binaryBodiesStayOutAndItsRecordedOnce() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("PNG…"))
        val c = Midge.openConnection(server.url("/logo.png").toUrl())
        c.inputStream.use { it.readBytes() }
        c.disconnect()
        val e = events().single()
        assertFalse(e.has("responseBody"))
    }

    @Test fun otherClientsReportByHand() {
        Midge.network("get", "https://api.example.com/x", 503, 120, responseBody = "busy", error = null)
        val e = events().single()
        assertEquals("GET", e.getString("method"))
        assertEquals(503, e.getInt("status"))
        assertEquals("busy", e.getString("responseBody"))
    }

    @Test fun anAlreadyWatchedConnectionIsntWrappedTwice() {
        val c = Midge.openConnection(URL(server.url("/").toString()))
        assertTrue(Midge.watch(c) === c)
    }
}
