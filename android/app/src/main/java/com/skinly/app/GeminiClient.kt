package com.skinly.app

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

class GeminiClient {

    // Paste your own key from Google AI Studio here. Never commit a real key.
    private val GEMINI_API_KEY = "YOUR_GEMINI_API_KEY"

    private val model = GenerativeModel(
        modelName = "gemini-2.0-flash",
        apiKey    = GEMINI_API_KEY,
        generationConfig = generationConfig {
            temperature     = 0.4f
            maxOutputTokens = 300
        }
    )

    suspend fun explain(
        diagnosis: String,
        confidence: Float,
        allScores: Map<String, Float>
    ): String {
        val scoresText = allScores.entries
            .sortedByDescending { it.value }
            .joinToString("\n") { "  - ${it.key}: ${"%.1f".format(it.value * 100)}%" }

        val prompt = """
            You are a friendly skin health assistant. A user scanned their skin
            using a UV + visible light camera with an AI model.

            AI result:
            - Finding: $diagnosis (${"%.1f".format(confidence * 100)}% confidence)
            - All scores:
            $scoresText

            In plain, simple language (3-4 sentences):
            1. What is $diagnosis in simple terms
            2. What the scan likely detected
            3. Whether they should see a dermatologist

            Do NOT be alarmist. Do NOT give medical advice. Keep it simple.
        """.trimIndent()

        return try {
            model.generateContent(prompt).text
                ?: "Unable to generate explanation. Please consult a dermatologist."
        } catch (e: Exception) {
            "$diagnosis detected at ${"%.1f".format(confidence * 100)}% confidence. Please consult a dermatologist."
        }
    }
}