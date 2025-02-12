package com.fynd.hardwaresdk.hardware

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.Keep
import androidx.core.app.ActivityCompat.startActivityForResult
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.EscPosPrinterCommands
import com.dantsu.escposprinter.connection.DeviceConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.tcp.TcpConnection
import com.dantsu.escposprinter.connection.usb.UsbConnection
import com.dantsu.escposprinter.connection.usb.UsbPrintersConnections
import com.zebra.barcode.sdk.sms.ConfigurationUpdateEvent
import com.zebra.scannercontrol.DCSSDKDefs
import com.zebra.scannercontrol.DCSScannerInfo
import com.zebra.scannercontrol.FirmwareUpdateEvent
import com.zebra.scannercontrol.IDcsScannerEventsOnReLaunch
import com.zebra.scannercontrol.IDcsSdkApiDelegate
import com.zebra.scannercontrol.SDKHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Serializable


const val ACTION_BARCODE_SCANNED = "action_barcode_scanned"
const val ACTION_BARCODE_SCANNED_DATAWEDGE = "com.zebra.datawedge.scan"
const val DATAWEDGE_DATA = "com.symbol.datawedge.data_string"
const val DATAWEDGE_TYPE = "com.symbol.datawedge.label_type"
const val EXTRA_BARCODE = "extra_barcode"
const val REQUEST_ENABLE_BT = 101

@SuppressLint("NewApi", "MissingPermission")
class HardwareRepoImpl(
    private val context: Application,
) : HardwareRepo, IDcsSdkApiDelegate, IDcsScannerEventsOnReLaunch {

    private val ACTION_USB_PERMISSION = "com.universalpos.data.repository.hardware.USB_PERMISSION"

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager?
                    val usbDevice: UsbDevice? =
                        intent.extras?.getParcelable(UsbManager.EXTRA_DEVICE)
                    if (
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    ) {
                        if (usbManager != null && usbDevice != null) {
                            connectToPrinter(usbDevice)
                        }
                    }
                }
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val attachedDevice: UsbDevice =
                    intent.extras?.getParcelable(UsbManager.EXTRA_DEVICE) ?: return
                handleAttachedUsbDevice(attachedDevice)
                onUsbConnected()
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                handleUsbDetachEvent()
            }
        }
    }

    private val bluetoothManager by lazy { context.getSystemService(BluetoothManager::class.java) }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                handleBluetoothStateChange(intent.extras?.getInt(BluetoothAdapter.EXTRA_STATE))
            }
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                handleWifiStateChange(intent.extras?.getInt(WifiManager.EXTRA_WIFI_STATE))
            } else if (intent.action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
                handleWifiNetworkStateChange(
                    intent.extras?.getParcelable(WifiManager.EXTRA_NETWORK_INFO)
                )
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(
                usbReceiver,
                getUsbReceiverIntentFilter(),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                usbReceiver,
                getUsbReceiverIntentFilter()
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(
                usbPermissionReceiver,
                getUsbHostPermissionIntentFilter(),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                usbPermissionReceiver,
                getUsbHostPermissionIntentFilter()
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(
                bluetoothReceiver,
                getBluetoothReceiverIntentFilter(),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                bluetoothReceiver,
                getBluetoothReceiverIntentFilter(),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(
                wifiReceiver,
                getWifiReceiverIntentFilter(),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                wifiReceiver,
                getWifiReceiverIntentFilter()
            )
        }
    }

    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }

    private val usbManager by lazy { context.getSystemService(Context.USB_SERVICE) as UsbManager }

    private lateinit var sdkHandler: SDKHandler
    private val scannerInfoList = mutableListOf<DCSScannerInfo>()
    private val _scannerStatus = MutableStateFlow<ScannerStatus>(ScannerStatus.Disconnected)
    private val listOfScannerVendorId = listOf(3034, 1504, 3034)
    private val listOfScannerProductId = listOf(33106, 6400, 1003)

    private var printer: EscPosPrinter? = null
    private val _printerStatus = MutableStateFlow<PrinterStatus>(PrinterStatus.Disconnected)
    private var printerCommands: EscPosPrinterCommands? = null
    private var deviceConnection: DeviceConnection? = null

    override val scannerStatus: StateFlow<ScannerStatus>
        get() = _scannerStatus.asStateFlow()

    override val printerStatus: StateFlow<PrinterStatus>
        get() = _printerStatus.asStateFlow()

    override val isPrinterConnected: Boolean
        get() = printer != null

    override fun onUsbConnected() {
        usbManager.deviceList.values.forEach {
            handleAttachedUsbDevice(it)
        }
    }

    override fun connectToUsbPrinter() {
        _printerStatus.value = PrinterStatus.ConnectingWithUSB
    }

    override fun askToTurnOnBluetooth(context: Activity) {
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(context, enableBtIntent, REQUEST_ENABLE_BT, null)
        }
    }

    override fun connectToBluetoothPrinter() {

        if (printer != null) {
            Timber.d ("A printer is already connected. Not doing anything")
            return
        }
        _printerStatus.value = PrinterStatus.ConnectingWithBluetooth
        val btPrinter = bluetoothAdapter.bondedDevices?.firstOrNull()
        if (btPrinter == null) {
            _printerStatus.value = PrinterStatus.ConnectionFailed
            return
        }
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    deviceConnection = BluetoothConnection(btPrinter)
                    this@HardwareRepoImpl.printerCommands =
                        deviceConnection?.let { EscPosPrinterCommands(it) }
                            ?: run { EscPosPrinterCommands(BluetoothConnection(btPrinter)) }
                    this@HardwareRepoImpl.printer = EscPosPrinter(
                        this@HardwareRepoImpl.printerCommands, 203, 56f, 36
                    )
                }
                _printerStatus.value = PrinterStatus.Connected(PrinterConnectionMode.Bluetooth)
            } catch (e: Exception) {
                _printerStatus.value = PrinterStatus.ConnectionFailed
                Timber.e(e)
            }
        }
    }

    override fun connectToWifiPrinter(ipAddress: String, portNumber: String) {
        if (printer != null) {
            Timber.i("A printer is already connected. Not doing anything")
            return
        }
        _printerStatus.value = PrinterStatus.ConnectingWithWiFi
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    deviceConnection = TcpConnection(ipAddress, portNumber.toInt(), 6000)
                    this@HardwareRepoImpl.printerCommands =
                        deviceConnection?.let { EscPosPrinterCommands(it) } ?: run {
                            EscPosPrinterCommands(
                                TcpConnection(
                                    ipAddress, portNumber.toInt(), 6000
                                )
                            )
                        }
                    this@HardwareRepoImpl.printer = EscPosPrinter(
                        this@HardwareRepoImpl.printerCommands, 203, 56f, 36
                    )
                }
                _printerStatus.value = PrinterStatus.Connected(PrinterConnectionMode.Wifi)
            } catch (e: Exception) {
                _printerStatus.value = PrinterStatus.ConnectionFailed
                Timber.e(e)
            }
        }
    }

    override fun print(text: String, shouldOpenDrawer: Boolean) {
        if (printer == null) return
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (shouldOpenDrawer) {
                        printer?.printFormattedTextAndOpenCashBox(text, 1f)
                    } else {
                        printer?.printFormattedTextAndCut(text)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    override fun print(bitmap: Bitmap, shouldOpenDrawer: Boolean, onSuccess: () -> Unit) {
        if (printer == null) return

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val byteArray = EscPosPrinterCommands.bitmapToBytes(bitmap, false)
                printerCommands?.printImage(byteArray)
                printerCommands?.cutPaper()
                if (shouldOpenDrawer) openCashDrawer()

                onSuccess()
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    override fun openCashDrawer() {
        try {
            printerCommands?.openCashBox()
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    override suspend fun printImage(byteImage: ByteArray) {
        deviceConnection?.let {
            if (!it.isConnected) return
            val bytesToPrint = arrayOf(byteImage)
            for (bytes in bytesToPrint) {
                it.write(bytes)
                it.send()
            }
        }
    }

    private fun handleAttachedUsbDevice(device: UsbDevice) {

        val isVendorIdValid = device.vendorId in listOfScannerVendorId
        val isProductIdValid = device.productId in listOfScannerProductId

        val usbPrinter = UsbPrintersConnections.selectFirstConnected(context)
        if (usbPrinter != null) {
            checkUsbHostPermission(usbPrinter.device)
        }
        if (isVendorIdValid && isProductIdValid) {
            initializeScannerSdkAndConnect()
        }
    }

    @SuppressLint("MutableImplicitPendingIntent")
    private fun checkUsbHostPermission(usbDevice: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager != null) {
            val implicitIntent = Intent(ACTION_USB_PERMISSION)
            val implicitPendingIntent = if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent.getBroadcast(
                    context,
                    0,
                    implicitIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
                )
            } else {
                PendingIntent.getBroadcast(context, 0, implicitIntent, PendingIntent.FLAG_MUTABLE)
            }
            usbManager.requestPermission(usbDevice, implicitPendingIntent)
        }
    }

    private fun handleUsbDetachEvent() {
        try {
            val printer = UsbPrintersConnections.selectFirstConnected(context)
            if (printer == null) {
                this.printer = null
                _printerStatus.value = PrinterStatus.Disconnected
            }
        } catch (e: Exception) {
            showToast(e.message.orEmpty())
            Timber.e(e)
        }
    }

    private fun connectToPrinter(device: UsbDevice) {
        if (printer != null) {
            Timber.i("A printer is already connected. Not doing anything")
            return
        }
        _printerStatus.value = PrinterStatus.ConnectingWithUSB
        try {
            deviceConnection = UsbConnection(usbManager, device)
            printerCommands = deviceConnection?.let { EscPosPrinterCommands(it) } ?: run {
                EscPosPrinterCommands(UsbConnection(usbManager, device))
            }
            this.printer = EscPosPrinter(
                this@HardwareRepoImpl.printerCommands, 203, 56f, 36
            )
            _printerStatus.value = PrinterStatus.Connected(PrinterConnectionMode.USB)
        } catch (e: Exception) {
            Timber.e(e)
            _printerStatus.value = PrinterStatus.Disconnected
        }
    }

    private fun initializeScannerSdkAndConnect() {
        if (!::sdkHandler.isInitialized) {
            sdkHandler = SDKHandler(context, true)
        }
        try {
            sdkHandler.apply {
                dcssdkEnableAvailableScannersDetection(true)
                dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_SNAPI)
                dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_USB_CDC)
                dcssdkSubsribeForEvents(255)
                dcssdkSetDelegate(this@HardwareRepoImpl)
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
        updateScannersList()
        connectToScanner()
    }

    private fun updateScannersList() {
        scannerInfoList.clear()
        val scannerTreeList = ArrayList<DCSScannerInfo>()
        sdkHandler.dcssdkGetAvailableScannersList(scannerTreeList)
        sdkHandler.dcssdkGetActiveScannersList(scannerTreeList)
        createFlatScannerList(scannerTreeList)
    }

    private fun createFlatScannerList(scannerTreeList: List<DCSScannerInfo>) {
        for (s in scannerTreeList) {
            addToScannerList(s)
        }
    }

    private fun addToScannerList(s: DCSScannerInfo) {
        scannerInfoList.add(s)
        if (s.auxiliaryScanners != null) {
            for (aux in s.auxiliaryScanners.values) {
                addToScannerList(aux)
            }
        }
    }

    private fun connectToScanner() {
        try {
            scannerInfoList.addAll(sdkHandler.dcssdkGetAvailableScannersList())
            sdkHandler.dcssdkGetAvailableScannersList(scannerInfoList)

            val connectedScanner = scannerInfoList.firstOrNull()
            if (connectedScanner != null) {
                val scannerId = connectedScanner.scannerID
                val connectResult =
                    sdkHandler.dcssdkEstablishCommunicationSession(scannerId)
                Timber.d("connectToScanner Result $connectResult")
            } else {
                Timber.e("No Scanner Found")
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun sendBarcodeBroadcast(barcode: ByteArray?) {
        if (barcode == null) return
        val intent = Intent(ACTION_BARCODE_SCANNED).apply {
            putExtra(EXTRA_BARCODE, String(barcode))
        }
        context.sendBroadcast(intent)
    }

    private fun getUsbHostPermissionIntentFilter(): IntentFilter {
        return IntentFilter(ACTION_USB_PERMISSION)
    }

    private fun getUsbReceiverIntentFilter(): IntentFilter {
        return IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
    }

    private fun getBluetoothReceiverIntentFilter(): IntentFilter {
        return IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED).apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
    }

    private fun getWifiReceiverIntentFilter(): IntentFilter {
        return IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
    }

    private fun handleBluetoothStateChange(state: Int?) {
        // Different bluetooth states
        // BluetoothAdapter.STATE_CONNECTED
        // BluetoothAdapter.STATE_CONNECTING
        // BluetoothAdapter.STATE_DISCONNECTED
        // BluetoothAdapter.STATE_DISCONNECTING
        // BluetoothAdapter.STATE_OFF
        // BluetoothAdapter.STATE_ON
        // BluetoothAdapter.STATE_TURNING_OFF
        // BluetoothAdapter.STATE_TURNING_ON
        val printerStatus = printerStatus.value
        if (
            (state == BluetoothAdapter.STATE_DISCONNECTED || state == BluetoothAdapter.STATE_OFF) &&
            printerStatus is PrinterStatus.Connected &&
            printerStatus.mode is PrinterConnectionMode.Bluetooth
        ) {
            printer = null
            _printerStatus.value = PrinterStatus.Disconnected
        }
    }

    private fun handleWifiStateChange(state: Int?) {
        // Different wifi states
        // WifiManager.WIFI_STATE_DISABLED
        // WifiManager.WIFI_STATE_DISABLING
        // WifiManager.WIFI_STATE_ENABLED
        // WifiManager.WIFI_STATE_ENABLING
        // WifiManager.WIFI_STATE_UNKNOWN
        val printerStatus = printerStatus.value
        if (
            state == WifiManager.WIFI_STATE_DISABLED &&
            printerStatus is PrinterStatus.Connected &&
            printerStatus.mode is PrinterConnectionMode.Wifi
        ) {
            printer = null
            _printerStatus.value = PrinterStatus.Disconnected
        }
    }

    private fun handleWifiNetworkStateChange(info: NetworkInfo?) {
        if (info == null) return
        val printerStatus = printerStatus.value
        if (
            info.state != NetworkInfo.State.CONNECTED &&
            printerStatus is PrinterStatus.Connected &&
            printerStatus.mode is PrinterConnectionMode.Wifi
        ) {
            printer = null
            _printerStatus.value = PrinterStatus.Disconnected
        }
    }

    private fun showToast(message: String) {
        Timber.d(">>>>>>> showToast $message")
    }

    //#region Zebra Scanner Callbacks

    override fun dcssdkEventScannerAppeared(p0: DCSScannerInfo) {
        showToast("dcssdkEventScannerAppeared")
        _scannerStatus.value = ScannerStatus.Connecting
        connectToScanner()
    }

    override fun dcssdkEventScannerDisappeared(p0: Int) {
        showToast("dcssdkEventScannerDisappeared")
        _scannerStatus.value = ScannerStatus.Disconnected
    }

    override fun dcssdkEventCommunicationSessionEstablished(p0: DCSScannerInfo) {
        showToast("dcssdkEventCommunicationSessionEstablished")
        _scannerStatus.value = ScannerStatus.Connected
    }

    override fun dcssdkEventCommunicationSessionTerminated(p0: Int) {
        showToast("dcssdkEventCommunicationSessionTerminated")
        _scannerStatus.value = ScannerStatus.Disconnected
    }

    override fun dcssdkEventBarcode(p0: ByteArray, p1: Int, p2: Int) {
        showToast("dcssdkEventBarcode: ${String(p0)}")
        sendBarcodeBroadcast(p0)
    }

    override fun dcssdkEventImage(p0: ByteArray, p1: Int) {
        showToast("dcssdkEventImage")
    }

    override fun dcssdkEventVideo(p0: ByteArray, p1: Int) {
        showToast("dcssdkEventVideo")
    }

    override fun dcssdkEventBinaryData(p0: ByteArray, p1: Int) {
        showToast("dcssdkEventBinaryData")
    }

    override fun dcssdkEventFirmwareUpdate(p0: FirmwareUpdateEvent) {
        showToast("dcssdkEventFirmwareUpdate")
    }

    override fun dcssdkEventAuxScannerAppeared(p0: DCSScannerInfo, p1: DCSScannerInfo) {
        showToast("dcssdkEventAuxScannerAppeared")
    }

    override fun dcssdkEventConfigurationUpdate(p0: ConfigurationUpdateEvent) {
        showToast("dcssdkEventConfigurationUpdate")
    }

    override fun onLastConnectedScannerDetect(p0: BluetoothDevice): Boolean {
        showToast("onLastConnectedScannerDetect")
        return true
    }

    override fun onConnectingToLastConnectedScanner(p0: BluetoothDevice) {
        showToast("onConnectingToLastConnectedScanner")
    }

    override fun onScannerDisconnect() {
        showToast("onScannerDisconnect")
        _scannerStatus.value = ScannerStatus.Disconnected
    }

    //endregion

}

@Keep
sealed class ScannerStatus : Serializable {
    @Keep
    object Connecting : ScannerStatus()

    @Keep
    object Connected : ScannerStatus()

    @Keep
    object Disconnected : ScannerStatus()
}

@Keep
sealed class PrinterStatus : Serializable {
    @Keep
    object ConnectingWithUSB : PrinterStatus()

    @Keep
    object ConnectingWithWiFi : PrinterStatus()

    @Keep
    object ConnectingWithBluetooth : PrinterStatus()

    @Keep
    class Connected(val mode: PrinterConnectionMode) : PrinterStatus()

    @Keep
    object ConnectionFailed : PrinterStatus()

    @Keep
    object Disconnected : PrinterStatus()
}

@Keep
sealed class PrinterConnectionMode : Serializable {
    @Keep
    object USB : PrinterConnectionMode()

    @Keep
    object Wifi : PrinterConnectionMode()

    @Keep
    object Bluetooth : PrinterConnectionMode()
}