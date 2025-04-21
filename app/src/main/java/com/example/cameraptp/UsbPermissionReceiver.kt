package com.example.cameraptp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi

class UsbPermissionReceiver : BroadcastReceiver() {

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("PTP", "Broadcast received: ${intent.action}")

        val extras = intent.extras
        if (extras != null) {
            Log.d("PTP", "Intent extras:")
            for (key in extras.keySet()) {
                Log.d("PTP", "  $key -> ${extras.get(key)}")
            }
        } else {
            Log.d("PTP", "No extras found in intent.")
        }

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            Log.d("PTP", "USB device attached")
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
            }



            device?.let {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                Log.d("PTP", "Has permission already? ${usbManager.hasPermission(it)}")
                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, UsbPermissionReceiver::class.java).setAction("com.example.cameraptp.USB_PERMISSION"),
                    PendingIntent.FLAG_MUTABLE
                )

                Log.d("PTP", "Requesting permission for device: ${device.deviceName}")
                usbManager.requestPermission(it, permissionIntent)
            }
        }

        if ("com.example.cameraptp.USB_PERMISSION" == intent.action) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                Log.d("PTP", "Permission granted for device: ${device.deviceName}")
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val connection: UsbDeviceConnection? = usbManager.openDevice(device)
                if (connection != null) {
                    Log.d("PTP", "Connection opened for device: ${device.deviceName}")
                    val activity = context as MainActivity
                    activity.setupPtpConnection(device, connection)
                    Toast.makeText(context, "Camera connected!", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("PTP", "Failed to open connection for device: ${device.deviceName}")
                    Toast.makeText(context, "Failed to open connection", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e("PTP", "Permission denied for USB device: ${device?.deviceName}")
                Toast.makeText(context, "Permission denied for USB device", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
