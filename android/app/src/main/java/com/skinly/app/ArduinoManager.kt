package com.skinly.app

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.delay

class ArduinoManager(private val context: Context) {

    private var port: com.hoho.android.usbserial.driver.UsbSerialPort? = null
    var isConnected = false
        private set

    fun connect(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) return false

        val driver = drivers[0]
        val connection = usbManager.openDevice(driver.device) ?: return false

        port = driver.ports[0]
        port?.open(connection)
        port?.setParameters(
            9600,
            com.hoho.android.usbserial.driver.UsbSerialPort.DATABITS_8,
            com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1,
            com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE
        )
        isConnected = true
        return true
    }

    private fun send(cmd: String) {
        port?.write((cmd + "\n").toByteArray(), 1000)
    }

    private fun readResponse(timeoutMs: Long = 2000): String {
        val buf = ByteArray(64)
        val start = System.currentTimeMillis()
        val sb = StringBuilder()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val n = port?.read(buf, 200) ?: 0
            if (n > 0) sb.append(String(buf, 0, n))
            if (sb.contains("\n")) break
        }
        return sb.toString().trim()
    }

    suspend fun uvOn(): Boolean {
        send("ON")
        delay(500)
        return readResponse().contains("LIGHT_ON")
    }

    suspend fun uvOff(): Boolean {
        send("OFF")
        return readResponse().contains("LIGHT_OFF")
    }

    fun disconnect() {
        port?.close()
        isConnected = false
    }
}