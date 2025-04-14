package com.example.cameraptp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.widget.Toast

class UsbPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

            device?.let {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent("com.example.cameraptp.USB_PERMISSION"),
                    PendingIntent.FLAG_IMMUTABLE
                )

                usbManager.requestPermission(it, permissionIntent)
            }
        }

        if ("com.example.cameraptp.USB_PERMISSION" == intent.action) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val connection: UsbDeviceConnection? = usbManager.openDevice(device)
                if (connection != null) {
                    val activity = context as MainActivity
                    activity.setupPtpConnection(device, connection)
                    Toast.makeText(context, "Camera connected!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to open connection", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Permission denied for USB device", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
