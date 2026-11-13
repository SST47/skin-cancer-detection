package com.skinly.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    // --- CONFIGURATION ---
    // Update this URL every time you restart ngrok
    private val NGROK_URL = "https://YOUR-NGROK-URL.ngrok-free.dev/predict"

    private lateinit var arduino:       ArduinoManager
    private lateinit var camera:        CameraManager
    private lateinit var gemini:        GeminiClient
    private lateinit var tvStatus:      TextView
    private lateinit var tvResult:      TextView
    private lateinit var imgVisible:    ImageView
    private lateinit var imgUV:         ImageView
    private lateinit var cameraPreview: PreviewView
    private lateinit var btnScan:       MaterialButton
    private lateinit var btnTest:       MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus      = findViewById(R.id.tvStatus)
        tvResult      = findViewById(R.id.tvResult)
        imgVisible    = findViewById(R.id.imgVisible)
        imgUV         = findViewById(R.id.imgUV)
        cameraPreview = findViewById(R.id.cameraPreview)
        btnScan       = findViewById(R.id.btnScan)
        btnTest       = findViewById(R.id.btnTest)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }

        gemini  = GeminiClient()
        arduino = ArduinoManager(this)
        camera  = CameraManager(this, this, cameraPreview)

        camera.startCamera()
        connectArduino()

        btnScan.setOnClickListener { startScan() }
        btnTest.setOnClickListener { startTestScan() }
    }

    private fun connectArduino() {
        lifecycleScope.launch(Dispatchers.IO) {
            val connected = arduino.connect()
            withContext(Dispatchers.Main) {
                if (connected) {
                    tvStatus.text = "✓ Arduino connected"
                    btnScan.isEnabled = true
                } else {
                    tvStatus.text = "⚠ Arduino not found — Check OTG"
                }
            }
        }
    }

    private fun startScan() {
        toggleButtons(false)
        tvResult.text = ""

        lifecycleScope.launch {
            try {
                setStatus("🔦 UV light ON...")
                withContext(Dispatchers.IO) { arduino.uvOn() }
                delay(600)

                setStatus("📸 Capturing UV...")
                val uvBitmap = camera.capturePhoto()
                imgUV.setImageBitmap(uvBitmap)

                withContext(Dispatchers.IO) { arduino.uvOff() }
                delay(600)

                setStatus("💡 Flashlight ON...")
                withContext(Dispatchers.Main) { camera.torchOn() }
                delay(1500)

                setStatus("📸 Capturing Visible...")
                val visBitmap = camera.capturePhoto()
                imgVisible.setImageBitmap(visBitmap)

                withContext(Dispatchers.Main) { camera.torchOff() }

                setStatus("🧠 Remote AI analysis (Mac)...")
                val responseJson = withContext(Dispatchers.IO) {
                    callFlaskApi(NGROK_URL, visBitmap, uvBitmap)
                }

                processApiResponse(responseJson)

            } catch (e: Exception) {
                setStatus("❌ Error: ${e.message}")
                tvResult.text = "Scan failed: ${e.message}"
            } finally {
                withContext(Dispatchers.IO) { arduino.uvOff() }
                withContext(Dispatchers.Main) { camera.torchOff() }  // ← add this
                toggleButtons(true)
            }
        }
    }

    private fun startTestScan() {
        toggleButtons(false)
        tvResult.text = ""

        lifecycleScope.launch {
            try {
                setStatus("📸 Capturing test image...")
                val visBitmap = camera.capturePhoto()
                imgVisible.setImageBitmap(visBitmap)
                imgUV.setImageBitmap(visBitmap)

                setStatus("🧠 Remote AI analysis...")
                val responseJson = withContext(Dispatchers.IO) {
                    callFlaskApi(NGROK_URL, visBitmap, visBitmap)
                }

                processApiResponse(responseJson)

            } catch (e: Exception) {
                setStatus("❌ Error: ${e.message}")
            } finally {
                toggleButtons(true)
            }
        }
    }

    private suspend fun processApiResponse(json: JSONObject) {
        if (json.getBoolean("success")) {
            val diag = json.getString("diagnosis")
            val conf = (json.getDouble("confidence") / 100.0).toFloat()

            val scoresJson = json.getJSONObject("all_scores")
            val allScores = mutableMapOf<String, Float>()
            scoresJson.keys().forEach { key ->
                allScores[key] = (scoresJson.getDouble(key) / 100.0).toFloat()
            }

            setStatus("💬 Generating explanation...")
            val explanation = withContext(Dispatchers.IO) {
                gemini.explain(diag, conf, allScores)
            }

            setStatus("✓ Complete")
            showResult(diag, conf, allScores, explanation)
        } else {
            throw Exception(json.optString("error", "Server Error"))
        }
    }

    private fun callFlaskApi(url: String, vis: Bitmap, uv: Bitmap): JSONObject {
        val client = OkHttpClient()

        val jsonBody = JSONObject().apply {
            put("visible", bitmapToBase64(vis))
            put("uv", bitmapToBase64(uv))
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return JSONObject(response.body?.string() ?: "{}")
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun showResult(diag: String, conf: Float, scores: Map<String, Float>, expl: String) {
        tvResult.text = buildString {
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📋 DIAGNOSIS: $diag")
            appendLine("Confidence: ${"%.1f".format(conf * 100)}%")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("🤖 EXPLANATION")
            appendLine(expl)
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📊 PROBABILITIES")
            scores.entries.sortedByDescending { it.value }.forEach {
                appendLine("${it.key}: ${"%.1f".format(it.value * 100)}%")
            }
        }
    }

    private suspend fun setStatus(msg: String) = withContext(Dispatchers.Main) {
        tvStatus.text = msg
    }

    private fun toggleButtons(enabled: Boolean) {
        btnScan.isEnabled = enabled
        btnTest.isEnabled = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        arduino.disconnect()
    }
}