package com.example.cameraptp

import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.rupiapps.ptp.connection.ptpusb.PtpConnection_Usb
import com.rupiapps.ptp.connection.ptpusb.PtpUsbEndpoints
import com.rupiapps.ptp.connection.ptpusb.PtpUsbPort
import com.rupiapps.ptp.datacallbacks.DataCallback
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import com.example.cameraptp.UsbPermissionReceiver

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var downloadButton: Button
    private lateinit var usbManager: UsbManager
    private var ptpConnection: CustomPtpConnection? = null
    private val usbPermissionReceiver = UsbPermissionReceiver()


    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        downloadButton = findViewById(R.id.downloadButton)
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        downloadButton.setOnClickListener { downloadLatestPhoto() }
        downloadButton.isEnabled = false

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction("com.example.cameraptp.USB_PERMISSION")
        registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    fun setupPtpConnection(device: UsbDevice, connection: UsbDeviceConnection) {
        try {
            val ptpInterface = device.findPtpInterface() ?: throw Exception("No PTP interface found")
            val endpoints = AndroidPtpUsbEndpoints(connection, ptpInterface)
            val usbPort = PtpUsbPort(endpoints)

            ptpConnection = CustomPtpConnection(usbPort).apply {
                connectAndInit(byteArrayOf(), device.deviceName) {}
                runOnUiThread {
                    statusText.text = "Camera connected: ${device.deviceName}"
                    downloadButton.isEnabled = true
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                statusText.text = "Error: ${e.message}"
                Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadLatestPhoto() {
        val connection = ptpConnection ?: run {
            statusText.text = "No camera connected"
            return
        }

        try {
            connection.sendRequest(PtpOperationCode.OpenSession.value, 1, intArrayOf(1), 5000)

            val handles = connection.getObjectHandles(0xFFFFFFFF.toInt(), 0, 0xFFFFFFFF.toInt())

            if (handles.isEmpty()) {
                statusText.text = "No photos found"
                return
            }

            val latestHandle = handles.last()
            val photoFile = File(getExternalFilesDir(null), "photo_$latestHandle.jpg")

            connection.getObject(latestHandle, FileOutputStream(photoFile))

            runOnUiThread {
                statusText.text = "Saved: ${photoFile.name}"
                Toast.makeText(this, "Photo downloaded!", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            runOnUiThread {
                statusText.text = "Error: ${e.message}"
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun onDestroy() {
        ptpConnection?.disconnect()
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
    }

    class CustomPtpConnection(port: PtpUsbPort) : PtpConnection_Usb(port) {
        fun getObjectHandles(storageId: Int, formatCode: Int, parentObject: Int): List<Int> {
            val callback = ObjectHandlesCallback()
            requestData(callback, PtpOperationCode.GetObjectHandles.value, 1, intArrayOf(storageId, formatCode, parentObject), 5000)
            return callback.handles.toList()
        }

        fun getObject(handle: Int, outputStream: OutputStream) {
            val callback = ObjectDataCallback(outputStream)
            requestData(callback, PtpOperationCode.GetObject.value, 1, intArrayOf(handle), 10000)
        }
    }

    enum class PtpOperationCode(val value: Short) {
        OpenSession(0x1002),
        GetObjectHandles(0x1007),
        GetObject(0x1009)
    }

    inner class AndroidPtpUsbEndpoints(
        private val connection: UsbDeviceConnection,
        private val usbInterface: UsbInterface
    ) : PtpUsbEndpoints {

        private val inEndpoint = usbInterface.endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        private val outEndpoint = usbInterface.endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }

        override fun initalize() {
            connection.claimInterface(usbInterface, true)
        }

        override fun release() {
            connection.releaseInterface(usbInterface)
        }

        override fun writeDataOut(buffer: ByteArray?, length: Int): Int {
            return connection.bulkTransfer(outEndpoint, buffer, length, 5000)
        }

        override fun readDataIn(buffer: ByteArray?): Int {
            return connection.bulkTransfer(inEndpoint, buffer, buffer?.size ?: 0, 5000)
        }

        override fun getMaxPacketSizeOut(): Int = outEndpoint?.maxPacketSize ?: 512
        override fun getMaxPacketSizeIn(): Int = inEndpoint?.maxPacketSize ?: 512
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?): Int {
            return connection.controlTransfer(requestType, request, value, index, buffer, buffer?.size ?: 0, 5000)
        }

        override fun getMaxPacketSizeInterrupt(): Int = 64
        override fun readEvent(buffer: ByteArray?, bulk: Boolean) {
            TODO("Not yet implemented")
        }

        override fun setTimeOut(timeout: Int) {
            TODO("Not yet implemented")
        }
    }

    class ObjectHandlesCallback : DataCallback {
        val handles = mutableListOf<Int>()
        override fun receivedDataPacket(transactionid: Int, totaldatasize: Long, cumulateddatasize: Long, data: ByteArray?, offset: Int, length: Int) {
            if (data != null) {
                for (i in offset until offset + length step 4) {
                    if (i + 3 < data.size) {
                        val handle = (data[i + 3].toInt() and 0xFF shl 24) or
                                (data[i + 2].toInt() and 0xFF shl 16) or
                                (data[i + 1].toInt() and 0xFF shl 8) or
                                (data[i].toInt() and 0xFF)
                        handles.add(handle)
                    }
                }
            }
        }
    }

    class ObjectDataCallback(private val outputStream: OutputStream) : DataCallback {
        override fun receivedDataPacket(transactionid: Int, totaldatasize: Long, cumulateddatasize: Long, data: ByteArray?, offset: Int, length: Int) {
            if (data != null && length > 0) {
                outputStream.write(data, offset, length)
            }
        }
    }

    private fun UsbDevice.findPtpInterface(): UsbInterface? {
        for (i in 0 until interfaceCount) {
            val iface = getInterface(i)
            if (iface.interfaceClass == 6) return iface
        }
        return null
    }

    private val UsbInterface.endpoints: List<UsbEndpoint>
        get() = List(endpointCount) { getEndpoint(it) }
}
