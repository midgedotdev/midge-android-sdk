package dev.midge.sdk

import org.json.JSONObject

/**
 * What the app did, kept in memory for the last 5 minutes (at most 2 000 events), in the extension's event format
 * (`kind`, epoch-ms `at`), so the extension's bundler can redact and package it like any Midge report.
 */
internal object EventLog {
    private const val KEEP_MS = 5 * 60 * 1000.0
    private const val MAX_EVENTS = 2000
    private val events = ArrayDeque<JSONObject>()

    fun now(): Double = System.currentTimeMillis().toDouble()

    @Synchronized
    fun add(event: JSONObject) {
        events.addLast(event)
        val oldest = now() - KEEP_MS
        while (events.isNotEmpty() && events.first().optDouble("at", 0.0) < oldest) events.removeFirst()
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    @Synchronized
    fun between(from: Double, to: Double): List<JSONObject> = events.filter { it.optDouble("at", 0.0) in from..to }

    @Synchronized
    fun clear() = events.clear()
}
