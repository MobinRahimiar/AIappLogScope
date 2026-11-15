package com.logscope.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AiClient {
    fun redact(input: String): String {
        var s = input
        s = s.replace(Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._-]+"), "\$1[REDACTED]")
        s = s.replace(Regex("(?i)(api[_-]?key|token|password|passwd)(\\s*[:=]\\s*)[^\\s,;]+"), "\$1\$2[REDACTED]")
        s = s.replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[EMAIL]")
        s = s.replace(Regex("(?<!\\d)(?:\\+?\\d[ .()-]?){8,14}\\d(?!\\d)"), "[PHONE]")
        return s
    }

    fun analyze(endpoint: String, model: String, apiKey: String, logs: String): String {
        require(endpoint.startsWith("https://")) { "AI endpoint must use HTTPS." }
        val url = URL(endpoint.trimEnd('/') + if (endpoint.endsWith("/chat/completions")) "" else "/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 30000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val prompt = "Analyze these Android diagnostic logs. Separate observed facts from hypotheses. Explain likely causes and safe next diagnostic steps. Do not suggest destructive commands.\n\n" + redact(logs).take(24000)
            val body = JSONObject().put("model", model).put("temperature", 0.2).put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", "You are a careful Android diagnostics assistant. Do not claim certainty without evidence.")).put(JSONObject().put("role", "user").put("content", prompt))).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (conn.responseCode !in 200..299) throw IllegalStateException("AI request failed (${conn.responseCode}): ${response.take(500)}")
            return JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally { conn.disconnect() }
    }
}
