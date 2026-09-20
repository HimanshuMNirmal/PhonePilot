package com.phonepilot.ai

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class LlmProvider(val displayName: String, val defaultModel: String) {
    GEMINI("Google Gemini", "gemini-3.6-flash"),
    OPENAI("OpenAI", "gpt-4o-mini")
}

/**
 * Universal client for calling LLM APIs (Google Gemini and OpenAI-compatible).
 */
class LlmClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "PhonePilot"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Sends prompt to selected LLM provider and returns the raw JSON response text.
     */
    fun sendPrompt(
        provider: LlmProvider,
        apiKey: String,
        model: String = provider.defaultModel,
        systemPrompt: String,
        userPrompt: String,
        customBaseUrl: String? = null
    ): String {
        return when (provider) {
            LlmProvider.GEMINI -> callGemini(apiKey, model, systemPrompt, userPrompt)
            LlmProvider.OPENAI -> callOpenAi(apiKey, model, systemPrompt, userPrompt, customBaseUrl)
        }
    }

    private fun callGemini(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val effectiveModel = normalizeGeminiModel(model)
        return try {
            executeGeminiRequest(apiKey, effectiveModel, systemPrompt, userPrompt)
        } catch (e: Exception) {
            if ((e.message?.contains("503") == true || e.message?.contains("404") == true) && effectiveModel != "gemini-3.6-flash") {
                Log.w(TAG, "Gemini $effectiveModel failed (${e.message}); attempting fallback to gemini-3.6-flash...")
                executeGeminiRequest(apiKey, "gemini-3.6-flash", systemPrompt, userPrompt)
            } else {
                throw e
            }
        }
    }

    private fun normalizeGeminiModel(model: String): String {
        val trimmed = model.trim()
        return when {
            trimmed.startsWith("gemini-3.", ignoreCase = true) -> "gemini-3.6-flash"
            trimmed.isBlank() -> "gemini-3.6-flash"
            else -> trimmed
        }
    }

    private fun executeGeminiRequest(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val requestJson = JSONObject().apply {
            // System instruction
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })

            // Content
            val contents = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                })
            }
            put("contents", contents)

            // JSON generation config
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("responseMimeType", "application/json")
            })
        }

        val body = requestJson.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API error (HTTP ${response.code}): $responseBody")
                val errorMsg = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message")
                } catch (_: Exception) { null }
                throw IOException("Gemini API error (HTTP ${response.code}): ${errorMsg ?: responseBody}")
            }

            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw IOException("Gemini returned no response candidates")
            }

            val candidate = candidates.getJSONObject(0)
            val parts = candidate.getJSONObject("content").getJSONArray("parts")
            val rawText = parts.getJSONObject(0).getString("text")
            return extractJson(rawText)
        }
    }

    private fun callOpenAi(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        customBaseUrl: String?
    ): String {
        val baseUrl = customBaseUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: "https://api.openai.com/v1/chat/completions"

        val requestJson = JSONObject().apply {
            put("model", model)
            put("temperature", 0.1)
            put("response_format", JSONObject().put("type", "json_object"))

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
            put("messages", messages)
        }

        val body = requestJson.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "OpenAI API error (HTTP ${response.code}): $responseBody")
                val errorMsg = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message")
                } catch (_: Exception) { null }
                throw IOException("OpenAI API error (HTTP ${response.code}): ${errorMsg ?: responseBody}")
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                throw IOException("OpenAI returned no response choices")
            }

            val rawText = choices.getJSONObject(0).getJSONObject("message").getString("content")
            return extractJson(rawText)
        }
    }

    /**
     * Cleans codeblocks like ```json ... ``` if returned by the LLM.
     */
    private fun extractJson(raw: String): String {
        var trimmed = raw.trim()
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.removePrefix("```json")
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.removePrefix("```")
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.removeSuffix("```")
        }
        return trimmed.trim()
    }
}
