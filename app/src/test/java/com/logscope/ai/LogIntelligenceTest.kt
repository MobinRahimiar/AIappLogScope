package com.logscope.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogIntelligenceTest {
    @Test fun parsesErrorAndCrashCategory() {
        val raw = "11-14 10:11:12.123  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main"
        val parsed = LogParser.parse(raw)
        assertTrue(parsed != null)
        assertEquals("ERROR", parsed!!.level)
        assertEquals("AndroidRuntime", parsed.tag)
        assertEquals("CRASH", parsed.category)
    }

    @Test fun parsesWarningNetworkCategory() {
        val raw = "11-14 10:11:12.123  1234  1234 W ConnectivityService: Network validation timed out"
        val parsed = LogParser.parse(raw)!!
        assertEquals("WARN", parsed.level)
        assertEquals("NETWORK", parsed.category)
    }

    @Test fun localAnalyzerExplainsAnr() {
        val entry = LogEntity(timestamp=1L, level="ERROR", pid="1", tid="1", tag="ActivityManager", message="ANR in com.example.app", raw="ANR in com.example.app", category="ANR")
        assertTrue(LocalAnalyzer.analyze(listOf(entry)).any { it.title.contains("ANR") })
    }

    @Test fun redactsCommonSecrets() {
        val input = "api_key=abc123 email test@example.com"
        val output = AiClient.redact(input)
        assertTrue(output.contains("[REDACTED]"))
        assertTrue(output.contains("[EMAIL]"))
        assertTrue(!output.contains("abc123"))
    }
}
