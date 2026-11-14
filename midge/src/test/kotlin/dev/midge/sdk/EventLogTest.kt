package dev.midge.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class EventLogTest {
    @Before fun clear() = EventLog.clear()

    @Test fun keepsFiveMinutesAndAnswersByTime() {
        val now = EventLog.now()
        EventLog.add(JSONObject().put("kind", "console").put("at", now - 6 * 60_000).put("text", "too old"))
        EventLog.add(JSONObject().put("kind", "console").put("at", now - 1000).put("text", "a"))
        EventLog.add(JSONObject().put("kind", "console").put("at", now).put("text", "b"))
        assertEquals(listOf("a", "b"), EventLog.between(now - 60_000, now + 1).map { it.getString("text") })
        assertEquals(listOf("b"), EventLog.between(now - 10, now + 1).map { it.getString("text") })
    }

    @Test fun keepsAtMostTwoThousand() {
        val now = EventLog.now()
        repeat(2100) { EventLog.add(JSONObject().put("kind", "console").put("at", now).put("text", "$it")) }
        val all = EventLog.between(0.0, now + 1)
        assertEquals(2000, all.size)
        assertEquals("100", all.first().getString("text"))
    }
}
