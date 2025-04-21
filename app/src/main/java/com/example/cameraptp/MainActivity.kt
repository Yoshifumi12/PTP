package com.example.cameraptp

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rupiapps.ptp.connection.ptpusb.PtpConnection_Usb
import com.rupiapps.ptp.connection.ptpusb.PtpUsbEndpoints
import com.rupiapps.ptp.connection.ptpusb.PtpUsbPort
import com.rupiapps.ptp.datacallbacks.DataCallback
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlinx.coroutines.*


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

        downloadButton.setOnClickListener {
            lifecycleScope.launch {
                downloadAllPhotosAsync()
            }
        }

        downloadButton.isEnabled = false

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction("com.example.cameraptp.USB_PERMISSION")
        registerReceiver(usbPermissionReceiver, filter, RECEIVER_EXPORTED)

        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (device.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE || device.findPtpInterface() != null) {
                Log.d("PTP", "Camera Detected:")
                Log.d("PTP", "Device Name: ${device.deviceName}")
                Log.d("PTP", "Vendor ID: ${device.vendorId}")
                Log.d("PTP", "Product ID: ${device.productId}")
                Log.d("PTP", "Manufacturer Name: ${device.manufacturerName}")
                Log.d("PTP", "Product Name: ${device.productName}")
                Log.d("PTP", "Serial Number: ${device.serialNumber}")
                Log.d("PTP", "Device Class: ${device.deviceClass}")
                Log.d("PTP", "Device Subclass: ${device.deviceSubclass}")
                Log.d("PTP", "Device Protocol: ${device.deviceProtocol}")

                if (!usbManager.hasPermission(device)) {
                    val permissionIntent = PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent(this, UsbPermissionReceiver::class.java).setAction("com.example.cameraptp.USB_PERMISSION"),
                        PendingIntent.FLAG_MUTABLE
                    )
                    usbManager.requestPermission(device, permissionIntent)
                } else {
                    val connection = usbManager.openDevice(device)
                    if (connection != null) {
                        setupPtpConnection(device, connection)
                    }
                }
            }
        }

    }

    fun setupPtpConnection(device: UsbDevice, connection: UsbDeviceConnection) {
        Log.d("PTP", "setupPtpConnection() called")
        try {
            Log.d("PTP", "Finding PTP interface...")
            val ptpInterface = device.findPtpInterface() ?: throw Exception("No PTP interface found")
            Log.d("PTP", "PTP interface found")
            val endpoints = AndroidPtpUsbEndpoints(connection, ptpInterface)
            Log.d("PTP", "Endpoints created")

            val usbPort = PtpUsbPort(endpoints)
            Log.d("PTP", "USB Port created")

            ptpConnection = CustomPtpConnection(usbPort).apply {
                Log.d("PTP", "Calling connectAndInit...")
                connectAndInit(byteArrayOf(), device.deviceName) {}
                Log.d("PTP", "connectAndInit callback reached")
                runOnUiThread {
                    statusText.text = getString(R.string.camera_connected, device.deviceName)
                    downloadButton.isEnabled = true
                }
            }
        } catch (e: Exception) {
            Log.e("PTP", "Error in setupPtpConnection: ${e.message}", e)
            runOnUiThread {
                statusText.text = getString(R.string.error_message, e.message)
                Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun downloadAllPhotosAsync() = withContext(Dispatchers.IO) {
        Log.d("PTP", "downloadAllPhotosAsync() called")
        val connection = ptpConnection ?: run {
            withContext(Dispatchers.Main) {
                Log.w("PTP", "No camera connected")
                statusText.text = getString(R.string.no_camera_connected)
            }
            return@withContext
        }

        try {
            Log.d("PTP", "Sending OpenSession request")
            val transactionId = connection.getNextTransactionId()
            connection.sendRequest(PtpOperationCode.OpenSession.value, transactionId, intArrayOf(1), 5000)

            Log.d("PTP", "Getting object handles")
            val handles = connection.getObjectHandles(0xFFFFFFFF.toInt(), 0, 0xFFFFFFFF.toInt())
            Log.d("PTP", "$handles")

            Log.d("PTP", "Handles retrieved: ${handles.size}")
            if (handles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    statusText.text = getString(R.string.no_photos_found)
                }
                return@withContext
            }

            for (handle in handles) {
                try {
                    val fileName = "photo_$handle.jpg"
                    val photoFile = File(getExternalFilesDir(null), fileName)

                    Log.d("PTP", "Downloading handle $handle to $fileName")

                    connection.getObject(handle, FileOutputStream(photoFile))

                    Log.d("PTP", "Saved $fileName")
                } catch (e: Exception) {
                    Log.e("PTP", "Error downloading photo with handle $handle: ${e.message}", e)
                }
            }

            withContext(Dispatchers.Main) {
                statusText.text = "All photos downloaded!"
                Toast.makeText(this@MainActivity, "Done downloading all photos!", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e("PTP", "Error downloading photos: ${e.message}", e)
            withContext(Dispatchers.Main) {
                statusText.text = getString(R.string.error_message, e.message)
                Toast.makeText(this@MainActivity, "Download failed", Toast.LENGTH_SHORT).show()
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

        private var currentTransactionId = 1

        fun getNextTransactionId(): Int {
            val id = currentTransactionId
            currentTransactionId++
            return id
        }

        fun getObjectHandles(storageId: Int, formatCode: Int, parentObject: Int): List<Int> {
            val callback = ObjectHandlesCallback()
            val transactionId = getNextTransactionId()
            requestData(callback, PtpOperationCode.GetObjectHandles.value, transactionId, intArrayOf(storageId, formatCode, parentObject), 5000)
            return callback.handles.toList()
        }

        fun getObject(handle: Int, outputStream: OutputStream) {
            val callback = ObjectDataCallback(outputStream)
            val transactionId = getNextTransactionId()
            requestData(callback, PtpOperationCode.GetObject.value, transactionId, intArrayOf(handle), 10000)
        }
        override fun sendRequest(operationCode: Short, transactionId: Int, params: IntArray, timeout: Int) {
            super.sendRequest(operationCode, transactionId, params, timeout)
        }
    }

    enum class PtpOperationCode(val value: Short) {
        OpenSession(0x1002),
        GetObjectHandles(0x1007),
        GetObjectInfo(0x1008),
        GetObject(0x1009)

    }

    inner class AndroidPtpUsbEndpoints(
        private val connection: UsbDeviceConnection,
        private val usbInterface: UsbInterface
    ) : PtpUsbEndpoints {

        private val inEndpoint = usbInterface.endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        private val outEndpoint = usbInterface.endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }

        override fun initalize() {
            val claimed = connection.claimInterface(usbInterface, true)
            Log.d("PTP", "Interface claimed: $claimed")
        }

        override fun release() {
            connection.releaseInterface(usbInterface)
        }

        override fun writeDataOut(buffer: ByteArray?, length: Int): Int {
            return connection.bulkTransfer(outEndpoint, buffer, length, 30000)
        }

        override fun readDataIn(buffer: ByteArray?): Int {
            Log.d("PTP", "readDataIn() called. Buffer size: ${buffer?.size ?: 0}")

            if (buffer == null) return 0

            var totalRead = 0
            var attempt = 0
            val maxAttempts = 5
            var lastError = ""

            while (attempt < maxAttempts) {
                try {
                    val bytesRead = connection.bulkTransfer(inEndpoint, buffer, buffer.size, 10000)  // 10s timeout
                    if (bytesRead > 0) {
                        totalRead = bytesRead
                        Log.d("PTP", "Bulk transfer succeeded. Bytes read: $bytesRead")
                        break
                    } else {
                        lastError = "Bulk transfer failed with $bytesRead bytes read"
                        Log.w("PTP", "Attempt $attempt: $lastError")
                    }
                } catch (e: Exception) {
                    lastError = "Exception during bulk transfer: ${e.message}"
                    Log.e("PTP", "Attempt $attempt: $lastError", e)
                }

                attempt++
                if (attempt < maxAttempts) {
                    // Exponential backoff
                    Log.d("PTP", "Retrying in ${attempt * 1000}ms")
                    Thread.sleep(attempt * 1000L) // Increase wait time between retries
                }
            }

            if (totalRead <= 0) {
                Log.e("PTP", "bulkTransfer failed after $maxAttempts attempts. Last error: $lastError")
                throw RuntimeException("Failed to read data from IN endpoint after $maxAttempts attempts")
            }

            return totalRead
        }

        override fun getMaxPacketSizeOut(): Int = outEndpoint?.maxPacketSize ?: 512
        override fun getMaxPacketSizeIn(): Int = inEndpoint?.maxPacketSize ?: 512
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?): Int {
            return connection.controlTransfer(requestType, request, value, index, buffer, buffer?.size ?: 0, 5000)
        }

        override fun getMaxPacketSizeInterrupt(): Int = 64
        override fun readEvent(buffer: ByteArray?, bulk: Boolean) {
            // not used
        }

        override fun setTimeOut(timeout: Int) {
            // not used
        }
    }

    class ObjectHandlesCallback : DataCallback {
        val handles = mutableListOf<Int>()
        override fun receivedDataPacket(transactionid: Int, totaldatasize: Long, cumulateddatasize: Long, data: ByteArray?, offset: Int, length: Int) {
            if (data != null) {
                Log.d("PTP", "Received data: ${data.joinToString(", ")}")
                for (i in offset +4 until offset + length step 4) {
                    if (i + 3 < data.size) {
                        val handle = (data[i + 3].toInt() and 0xFF shl 24) or
                                (data[i + 2].toInt() and 0xFF shl 16) or
                                (data[i + 1].toInt() and 0xFF shl 8) or
                                (data[i].toInt() and 0xFF)
                        handles.add(handle)
                        Log.d("PTP", "Found handle: $handle")
                    }
                }
            }
        }
    }

    class ObjectDataCallback(private val outputStream: OutputStream) : DataCallback {
        override fun receivedDataPacket(
            transactionid: Int,
            totaldatasize: Long,
            cumulateddatasize: Long,
            data: ByteArray?,
            offset: Int,
            length: Int
        ) {
            if (data == null) {
                Log.e("PTP", "receivedDataPacket(): data is null!")
                return
            }
            if (length <= 0) {
                Log.w("PTP", "receivedDataPacket(): length is zero or negative: $length")
                return
            }
            try {
                outputStream.write(data, offset, length)
                Log.d("PTP", "Written data to output stream, size: $length")
            } catch (e: Exception) {
                Log.e("PTP", "Error writing to output stream: ${e.message}", e)
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
