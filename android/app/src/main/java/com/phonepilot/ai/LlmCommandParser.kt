package com.phonepilot.ai

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote LLM command parser connecting to OpenAI-compatible endpoints.
 * Operates strictly as a parser, feeding its structured action into the existing
 * Phase 2 ActionEngine without bypassing safety checks or validation.
 */
class LlmCommandParser(
    private val apiKey: String,
    private val endpointUrl: String = "https://api.openai.com/v1/chat/completions",
    private val model: String = "gpt-4o-mini",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000
) : AiCommandParser {

    companion object {
        private const val TAG = "PhonePilot"
    }

    override fun parse(command: String, context: SanitizedScreenContext?): ParseResult {
        val trimmedCommand = command.trim()
        if (trimmedCommand.isEmpty()) {
            return ParseResult.Failure("Command cannot be empty")
        }

        if (apiKey.isBlank()) {
            return ParseResult.Failure("LLM API key is not configured")
        }

        return try {
            val systemPrompt = SystemPromptBuilder.build(context)
            val requestBody = JSONObject().apply {
                put("model", model)
                put("temperature", 0.0)
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", trimmedCommand)
                    })
                }
                put("messages", messages)
            }

            val responseJsonStr = postHttpRequest(endpointUrl, requestBody.toString())
            val responseJson = JSONObject(responseJsonStr)
            val choices = responseJson.optJSONArray("choices")
            val rawContent = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()

            if (rawContent.isNullOrBlank()) {
                return ParseResult.Failure("Empty response received from LLM")
            }

            // Clean markdown code fence if model included ```json ... ```
            val cleanedJson = rawContent
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val action = ActionJsonMapper.fromJson(cleanedJson)
            Log.i(TAG, "LLM parsed action successfully: ${action::class.simpleName}")
            ParseResult.Success(action, reasoning = "LLM generated action: $cleanedJson")
        } catch (e: Exception) {
            Log.e(TAG, "LLM command parsing failed: ${e.message}")
            ParseResult.Failure("LLM parsing error: ${e.message}")
        }
    }

    private fun postHttpRequest(urlString: String, jsonBody: String): String {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(jsonBody)
            writer.flush()
        }

        val statusCode = conn.responseCode
        val inputStream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
        val responseBody = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }

        if (statusCode !in 200..299) {
            throw IllegalStateException("HTTP $statusCode: $responseBody")
        }

        return responseBody
    }
}
