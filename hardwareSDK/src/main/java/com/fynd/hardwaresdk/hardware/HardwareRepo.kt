package com.fynd.hardwaresdk.hardware

import android.app.Activity
import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

interface HardwareRepo {

    val scannerStatus: StateFlow<ScannerStatus>

    val printerStatus: StateFlow<PrinterStatus>

    val isPrinterConnected: Boolean

    fun onUsbConnected()
    fun connectToUsbPrinter()
    fun connectToBluetoothPrinter()

    fun askToTurnOnBluetooth(context:Activity)
    fun connectToWifiPrinter(ipAddress: String, portNumber: String)
    fun print(text: String, shouldOpenDrawer: Boolean)
    fun openCashDrawer()
    suspend fun printImage(byteImage: ByteArray)
    fun print(bitmap: Bitmap, shouldOpenDrawer: Boolean, onSuccess: () -> Unit)
}