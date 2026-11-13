package com.skinly.app

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64

class ModelInference(context: android.content.Context) {

    // ← your Mac's IP address
    private val SERVER_URL = "http://192.168.2.214:5000/predict"

    val CLASS_NAMES = mapOf(
        0 to "Melanocytic Nevi",
        1 to "Melanoma",
        2 to "Benign Keratosis",
        3 to "Basal Cell Carcinoma",
        4 to "Actinic Keratoses",
        5 to "Vascular Lesions",
        6 to "Dermatofibroma"
    )

    data class Result(
        val className: String,
        val confidence: Float,
        val allScores: Map<String, Float>
    )

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    fun predict(visibleBitmap: Bitmap, uvBitmap: Bitmap): Result {
        val url  = URL(SERVER_URL)
        val conn = url.openConnection() as HttpURLConnection

        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout    = 10000

        // Send images as base64
        val body = JSONObject().apply {
            put("visible", bitmapToBase64(visibleBitmap))
            put("uv",      bitmapToBase64(uvBitmap))
        }.toString()

        conn.outputStream.write(body.toByteArray())

        // Read response
        val response = conn.inputStream.bufferedReader().readText()
        val json     = JSONObject(response)

        if (!json.getBoolean("success")) {
            throw Exception(json.getString("error"))
        }

        val diagnosis  = json.getString("diagnosis")
        val confidence = json.getDouble("confidence").toFloat() / 100f

        val scoresJson = json.getJSONObject("all_scores")
        val allScores  = mutableMapOf<String, Float>()
        scoresJson.keys().forEach { key ->
            allScores[key] = scoresJson.getDouble(key).toFloat() / 100f
        }

        return Result(
            className  = diagnosis,
            confidence = confidence,
            allScores  = allScores
        )
    }
}