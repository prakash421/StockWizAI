package com.example.financestreamai

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ============================================================
// Data models for AI Cross-Validation
// ============================================================

/** Result from a single AI engine */
data class AiEngineResult(
    val engine: String,          // "Claude", "Gemini", "ChatGPT", "Perplexity"
    val verdict: String,         // "BUY", "SELL", "HOLD", "AVOID"
    val confidence: String,      // "High", "Medium", "Low"
    val reasoning: String,       // 1-2 sentence explanation
    val error: String? = null    // non-null if call failed
)

/** Aggregated cross-validation result for a stock */
data class AiCrossValidation(
    val ticker: String,
    val engines: List<AiEngineResult>,
    val consensus: String,       // "STRONG BUY", "BUY", "HOLD", "AVOID", "MIXED"
    val agreementPct: Int,       // % of engines that agree with consensus
    val summary: String,         // e.g. "3/4 AI engines rate TSLA as BUY with High confidence"
    val timestamp: Long = System.currentTimeMillis()
)

// ============================================================
// API Key Manager (encrypted local storage)
// ============================================================
object AiKeyManager {
    private const val PREFS_FILE = "ai_api_keys_encrypted"
    const val KEY_CLAUDE = "claude_api_key"
    const val KEY_GEMINI = "gemini_api_key"
    const val KEY_CHATGPT = "chatgpt_api_key"
    const val KEY_PERPLEXITY = "perplexity_api_key"
    const val KEY_GROK = "grok_api_key"
    private const val KEY_PROMPT_SHOWN = "ai_key_prompt_shown"

    private fun getPrefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to regular prefs if encrypted fails (rare, old devices)
        Log.e("AiKeyManager", "Encrypted prefs failed, using fallback", e)
        context.getSharedPreferences("ai_api_keys_fallback", Context.MODE_PRIVATE)
    }

    fun getKey(context: Context, keyName: String): String? =
        getPrefs(context).getString(keyName, null)?.takeIf { it.isNotBlank() }

    fun setKey(context: Context, keyName: String, value: String) {
        getPrefs(context).edit().putString(keyName, value.trim()).apply()
    }

    fun clearKey(context: Context, keyName: String) {
        getPrefs(context).edit().remove(keyName).apply()
    }

    fun hasAnyKeys(context: Context): Boolean {
        val prefs = getPrefs(context)
        return listOf(KEY_CLAUDE, KEY_GEMINI, KEY_CHATGPT, KEY_PERPLEXITY, KEY_GROK).any {
            prefs.getString(it, null)?.isNotBlank() == true
        }
    }

    fun getConfiguredEngines(context: Context): List<String> {
        val prefs = getPrefs(context)
        val engines = mutableListOf<String>()
        if (prefs.getString(KEY_CLAUDE, null)?.isNotBlank() == true) engines.add("Claude")
        if (prefs.getString(KEY_GEMINI, null)?.isNotBlank() == true) engines.add("Gemini")
        if (prefs.getString(KEY_CHATGPT, null)?.isNotBlank() == true) engines.add("ChatGPT")
        if (prefs.getString(KEY_PERPLEXITY, null)?.isNotBlank() == true) engines.add("Perplexity")
        if (prefs.getString(KEY_GROK, null)?.isNotBlank() == true) engines.add("Grok")
        return engines
    }

    fun wasPromptShown(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_PROMPT_SHOWN, false)

    fun markPromptShown(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_PROMPT_SHOWN, true).apply()
    }
}


// ============================================================
// AI Cross-Validator Engine
// ============================================================
object AiCrossValidator {
    private const val TAG = "AiCrossValidator"
    private val gson = Gson()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Cache results for 15 minutes to avoid repeated calls
    private val cache = mutableMapOf<String, AiCrossValidation>()
    private const val CACHE_TTL_MS = 15 * 60 * 1000L

    fun getCached(ticker: String): AiCrossValidation? {
        val cached = cache[ticker] ?: return null
        return if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) cached else {
            cache.remove(ticker)
            null
        }
    }

    /**
     * Cross-validate a stock recommendation across configured AI engines.
     * @param ticker Stock symbol
     * @param price Current price
     * @param recommendation The app's recommendation (e.g. "STRONG BUY")
     * @param signals Bullish signals list
     * @param warnings Bearish signals list
     * @param levels Key price levels
     * @param sector Stock sector
     */
    suspend fun validate(
        context: Context,
        ticker: String,
        price: Double,
        recommendation: String,
        signals: List<String>,
        warnings: List<String>,
        levels: StockLevels?,
        sector: String?,
        strategies: String? = null
    ): AiCrossValidation {
        // Check cache first
        getCached(ticker)?.let { return it }

        val prompt = buildPrompt(ticker, price, recommendation, signals, warnings, levels, sector, strategies)

        val results = coroutineScope {
            val tasks = mutableListOf<kotlinx.coroutines.Deferred<AiEngineResult>>()

            AiKeyManager.getKey(context, AiKeyManager.KEY_CLAUDE)?.let { key ->
                tasks.add(async(Dispatchers.IO) { callClaude(key, prompt) })
            }
            AiKeyManager.getKey(context, AiKeyManager.KEY_GEMINI)?.let { key ->
                tasks.add(async(Dispatchers.IO) { callGemini(key, prompt) })
            }
            AiKeyManager.getKey(context, AiKeyManager.KEY_CHATGPT)?.let { key ->
                tasks.add(async(Dispatchers.IO) { callChatGPT(key, prompt) })
            }
            AiKeyManager.getKey(context, AiKeyManager.KEY_PERPLEXITY)?.let { key ->
                tasks.add(async(Dispatchers.IO) { callPerplexity(key, prompt) })
            }
            AiKeyManager.getKey(context, AiKeyManager.KEY_GROK)?.let { key ->
                tasks.add(async(Dispatchers.IO) { callGrok(key, prompt) })
            }

            tasks.awaitAll()
        }

        val validation = buildConsensus(ticker, results)
        cache[ticker] = validation
        return validation
    }

    private fun buildPrompt(
        ticker: String, price: Double, recommendation: String,
        signals: List<String>, warnings: List<String>,
        levels: StockLevels?, sector: String?, strategies: String?
    ): String {
        val levelsStr = if (levels != null) {
            val parts = mutableListOf<String>()
            levels.support?.let { parts.add("Support: $${"%.2f".format(it)}") }
            levels.resistance?.let { parts.add("Resistance: $${"%.2f".format(it)}") }
            levels.target?.let { parts.add("Target: $${"%.2f".format(it)}") }
            levels.stopLoss?.let { parts.add("Stop Loss: $${"%.2f".format(it)}") }
            levels.riskReward?.let { parts.add("Risk/Reward: ${"%.1f".format(it)}:1") }
            if (parts.isNotEmpty()) "\nKey Levels: ${parts.joinToString(", ")}" else ""
        } else ""

        val strategyStr = if (!strategies.isNullOrBlank()) "\nStrategies available: $strategies" else ""

        return """You are a stock market analyst. Evaluate this stock recommendation and provide your independent assessment.

Stock: $ticker
Current Price: $${"%.2f".format(price)}
Sector: ${sector ?: "Unknown"}
Our Recommendation: $recommendation

Bullish Signals: ${if (signals.isNotEmpty()) signals.joinToString("; ") else "None"}
Bearish Signals/Warnings: ${if (warnings.isNotEmpty()) warnings.joinToString("; ") else "None"}$levelsStr$strategyStr

Respond in EXACTLY this JSON format and nothing else:
{"verdict":"BUY or SELL or HOLD or AVOID","confidence":"High or Medium or Low","reasoning":"One or two sentences explaining your assessment"}"""
    }

    // -------- Claude (Anthropic) --------
    private fun callClaude(apiKey: String, prompt: String): AiEngineResult {
        return try {
            val body = gson.toJson(mapOf(
                "model" to "claude-sonnet-4-20250514",
                "max_tokens" to 200,
                "messages" to listOf(mapOf("role" to "user", "content" to prompt))
            ))
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return AiEngineResult("Claude", "N/A", "N/A", "", error = "HTTP ${response.code}: ${responseBody.take(200)}")
            }
            parseClaudeResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Claude call failed", e)
            AiEngineResult("Claude", "N/A", "N/A", "", error = e.message ?: "Unknown error")
        }
    }

    private fun parseClaudeResponse(body: String): AiEngineResult {
        return try {
            val map = gson.fromJson(body, Map::class.java)
            val content = (map["content"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val text = (content?.get("text") as? String) ?: ""
            parseAiJson("Claude", text)
        } catch (e: Exception) {
            AiEngineResult("Claude", "N/A", "N/A", "", error = "Parse error: ${e.message}")
        }
    }

    // -------- Google Gemini --------
    private fun callGemini(apiKey: String, prompt: String): AiEngineResult {
        // Try gemini-2.0-flash first; fall back to gemini-1.5-flash if the model is unavailable
        val models = listOf("gemini-2.0-flash", "gemini-1.5-flash")
        var lastError = ""
        for (model in models) {
            try {
                val body = gson.toJson(mapOf(
                    "contents" to listOf(mapOf(
                        "parts" to listOf(mapOf("text" to prompt))
                    )),
                    "generationConfig" to mapOf("maxOutputTokens" to 300)
                ))
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                    .addHeader("content-type", "application/json")
                    .post(body.toRequestBody(JSON_TYPE))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    lastError = "HTTP ${response.code} ($model): ${responseBody.take(300)}"
                    Log.w(TAG, "Gemini model $model failed: $lastError")
                    // 404 = model not found → try next; 400/401/403 = auth/key issue → stop
                    if (response.code in listOf(400, 401, 403)) {
                        return AiEngineResult("Gemini", "N/A", "N/A", "", error = lastError)
                    }
                    continue
                }
                return parseGeminiResponse(responseBody)
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                Log.e(TAG, "Gemini call failed ($model)", e)
            }
        }
        return AiEngineResult("Gemini", "N/A", "N/A", "", error = lastError)
    }

    private fun parseGeminiResponse(body: String): AiEngineResult {
        return try {
            val map = gson.fromJson(body, Map::class.java)
            val candidates = map["candidates"] as? List<*>
            val content = (candidates?.firstOrNull() as? Map<*, *>)?.get("content") as? Map<*, *>
            val parts = content?.get("parts") as? List<*>
            val text = (parts?.firstOrNull() as? Map<*, *>)?.get("text") as? String ?: ""
            parseAiJson("Gemini", text)
        } catch (e: Exception) {
            AiEngineResult("Gemini", "N/A", "N/A", "", error = "Parse error: ${e.message}")
        }
    }

    // -------- OpenAI ChatGPT --------
    private fun callChatGPT(apiKey: String, prompt: String): AiEngineResult {
        return try {
            val body = gson.toJson(mapOf(
                "model" to "gpt-4o-mini",
                "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                "max_tokens" to 200
            ))
            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return AiEngineResult("ChatGPT", "N/A", "N/A", "", error = "HTTP ${response.code}: ${responseBody.take(200)}")
            }
            parseChatGPTResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "ChatGPT call failed", e)
            AiEngineResult("ChatGPT", "N/A", "N/A", "", error = e.message ?: "Unknown error")
        }
    }

    private fun parseChatGPTResponse(body: String): AiEngineResult {
        return try {
            val map = gson.fromJson(body, Map::class.java)
            val choices = map["choices"] as? List<*>
            val message = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
            val text = message?.get("content") as? String ?: ""
            parseAiJson("ChatGPT", text)
        } catch (e: Exception) {
            AiEngineResult("ChatGPT", "N/A", "N/A", "", error = "Parse error: ${e.message}")
        }
    }

    // -------- Perplexity --------
    private fun callPerplexity(apiKey: String, prompt: String): AiEngineResult {
        return try {
            val body = gson.toJson(mapOf(
                "model" to "sonar",
                "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                "max_tokens" to 200
            ))
            val request = Request.Builder()
                .url("https://api.perplexity.ai/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return AiEngineResult("Perplexity", "N/A", "N/A", "", error = "HTTP ${response.code}: ${responseBody.take(200)}")
            }
            parsePerplexityResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Perplexity call failed", e)
            AiEngineResult("Perplexity", "N/A", "N/A", "", error = e.message ?: "Unknown error")
        }
    }

    private fun parsePerplexityResponse(body: String): AiEngineResult {
        // Perplexity uses same format as OpenAI
        return try {
            val map = gson.fromJson(body, Map::class.java)
            val choices = map["choices"] as? List<*>
            val message = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
            val text = message?.get("content") as? String ?: ""
            parseAiJson("Perplexity", text)
        } catch (e: Exception) {
            AiEngineResult("Perplexity", "N/A", "N/A", "", error = "Parse error: ${e.message}")
        }
    }

    // -------- Grok (xAI) --------
    private fun callGrok(apiKey: String, prompt: String): AiEngineResult {
        return try {
            val body = gson.toJson(mapOf(
                "model" to "grok-3-mini-fast",
                "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                "max_tokens" to 200
            ))
            val request = Request.Builder()
                .url("https://api.x.ai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return AiEngineResult("Grok", "N/A", "N/A", "", error = "HTTP ${response.code}: ${responseBody.take(200)}")
            }
            parseGrokResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Grok call failed", e)
            AiEngineResult("Grok", "N/A", "N/A", "", error = e.message ?: "Unknown error")
        }
    }

    private fun parseGrokResponse(body: String): AiEngineResult {
        // Grok uses OpenAI-compatible format
        return try {
            val map = gson.fromJson(body, Map::class.java)
            val choices = map["choices"] as? List<*>
            val message = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
            val text = message?.get("content") as? String ?: ""
            parseAiJson("Grok", text)
        } catch (e: Exception) {
            AiEngineResult("Grok", "N/A", "N/A", "", error = "Parse error: ${e.message}")
        }
    }

    // -------- Common JSON parser for AI responses --------
    private fun parseAiJson(engine: String, text: String): AiEngineResult {
        // Extract JSON from response (may have markdown or text around it)
        val jsonMatch = Regex("""\{[^{}]*"verdict"[^{}]*\}""", RegexOption.DOT_MATCHES_ALL).find(text)
        val jsonStr = jsonMatch?.value ?: text.trim()
        return try {
            val parsed = gson.fromJson(jsonStr, Map::class.java)
            AiEngineResult(
                engine = engine,
                verdict = (parsed["verdict"] as? String)?.uppercase()?.trim() ?: "N/A",
                confidence = (parsed["confidence"] as? String)?.trim() ?: "N/A",
                reasoning = (parsed["reasoning"] as? String)?.trim() ?: ""
            )
        } catch (e: Exception) {
            // Try to extract verdict/reasoning from plain text
            val verdictMatch = Regex("""(?i)(BUY|SELL|HOLD|AVOID)""").find(text)
            AiEngineResult(
                engine = engine,
                verdict = verdictMatch?.value?.uppercase() ?: "N/A",
                confidence = "Low",
                reasoning = text.take(200).trim()
            )
        }
    }

    // -------- Consensus builder --------
    private fun buildConsensus(ticker: String, results: List<AiEngineResult>): AiCrossValidation {
        val successful = results.filter { it.error == null && it.verdict != "N/A" }
        val total = results.size

        if (successful.isEmpty()) {
            return AiCrossValidation(
                ticker = ticker,
                engines = results,
                consensus = "UNAVAILABLE",
                agreementPct = 0,
                summary = "AI cross-validation failed — check API keys"
            )
        }

        // Count verdicts
        val verdictCounts = successful.groupBy { normalizeVerdict(it.verdict) }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }

        val topVerdict = verdictCounts.first().first
        val topCount = verdictCounts.first().second
        val agreement = (topCount * 100) / successful.size

        // Determine consensus
        val consensus = when {
            agreement >= 75 && (topVerdict == "BUY") -> "STRONG BUY"
            agreement >= 75 -> topVerdict
            agreement >= 50 -> topVerdict
            else -> "MIXED"
        }

        // Weight by confidence
        val highConfBuys = successful.count { normalizeVerdict(it.verdict) == "BUY" && it.confidence.equals("High", true) }
        val adjustedConsensus = if (consensus == "BUY" && highConfBuys >= (successful.size * 0.75)) "STRONG BUY" else consensus

        val engineNames = successful.joinToString(", ") { it.engine }
        val summary = "$topCount/${successful.size} AI engines ($engineNames) rate $ticker as $adjustedConsensus" +
                if (results.size > successful.size) " (${results.size - successful.size} engine(s) unavailable)" else ""

        return AiCrossValidation(
            ticker = ticker,
            engines = results,
            consensus = adjustedConsensus,
            agreementPct = agreement,
            summary = summary
        )
    }

    private fun normalizeVerdict(verdict: String): String = when {
        verdict.contains("STRONG", true) && verdict.contains("BUY", true) -> "BUY"
        verdict.contains("BUY", true) -> "BUY"
        verdict.contains("SELL", true) -> "SELL"
        verdict.contains("HOLD", true) -> "HOLD"
        verdict.contains("AVOID", true) -> "AVOID"
        else -> verdict.uppercase()
    }

    fun clearCache() {
        cache.clear()
    }
}
