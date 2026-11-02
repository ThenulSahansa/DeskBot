package com.example.deskbot

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.deskbot.ui.screens.DeskBotMainScreen
import com.example.deskbot.ui.screens.DeskBotUiState
import com.example.deskbot.ui.theme.DeskBotTheme
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class RealBluetoothDeviceItem(
    val name: String,
    val address: String,
    val isBonded: Boolean,
    val device: BluetoothDevice,
    val isDeskBot: Boolean = false
)

class MainActivity : ComponentActivity() {

    private var isConnected = false
    private var isConnecting = false

    // Bluetooth properties
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var activeGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var firmwareCharacteristic: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = 23
    private val gattWriteLock = Any()
    private var pendingWriteLatch: CountDownLatch? = null
    private var pendingWriteSucceeded = false
    private var connectedDevice: BluetoothDevice? = null
    private var audioCharacteristic: BluetoothGattCharacteristic? = null
    private var displayCharacteristic: BluetoothGattCharacteristic? = null
    private var geminiManager: GeminiLiveManager? = null
    private var isAiSessionActive = false

    // Compose UI State
    private val uiState = mutableStateOf(DeskBotUiState())

    // Device picker bottom sheet references
    private var deviceBottomSheetDialog: BottomSheetDialog? = null
    private val realDevicesList = mutableListOf<RealBluetoothDeviceItem>()
    private var devicesAdapter: ArrayAdapter<RealBluetoothDeviceItem>? = null
    private var sheetScanProgressBar: ProgressBar? = null
    private var sheetEmptyContainer: View? = null

    // Update state
    private var isUpdateCheckActive = false
    private var isUpdateAvailable = false
    private var latestVersionTag: String? = null
    private var isFirmwareUpdateInProgress = false

    // BLE OTA UUIDs for ESP32-C3 DeskBot
    private val BLE_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val BLE_RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val BLE_STATUS_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val BLE_FIRMWARE_UUID = UUID.fromString("6E400004-B5A3-F393-E0A9-E50E24DCCA9E")
    private val BLE_AUDIO_UUID = UUID.fromString("6E400005-B5A3-F393-E0A9-E50E24DCCA9E")
    private val BLE_DISPLAY_UUID = UUID.fromString("6E400006-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBluetoothAndShowDevices()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required to connect to DeskBot.", Toast.LENGTH_LONG).show()
        }
    }

    // Enable Bluetooth launcher
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkBluetoothAndShowDevices()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled to connect.", Toast.LENGTH_SHORT).show()
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            handleDiscoveredBleDevice(result)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleDiscoveredBleDevice(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                sheetScanProgressBar?.visibility = View.GONE
                updateConnectionState(ConnectionState.IDLE)
                val msg = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Scan already in progress"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "BLE registration failed"
                    SCAN_FAILED_INTERNAL_ERROR -> "BLE internal hardware error"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scan unsupported"
                    else -> "BLE scan failed ($errorCode)"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDiscoveredBleDevice(result: ScanResult) {
        val device = result.device ?: return

        // STRICT REQUIREMENT: Only BLE devices. Exclude Bluetooth Classic (BR/EDR).
        if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            return
        }

        val scanRecord = result.scanRecord
        val advertisedName = (device.name?.takeIf { it.isNotBlank() }
            ?: scanRecord?.deviceName?.takeIf { it.isNotBlank() })

        // Check if advertising DeskBot's Nordic UART / OTA BLE Service UUID
        val hasDeskBotService = scanRecord?.serviceUuids?.any {
            it.uuid == BLE_SERVICE_UUID || it.uuid.toString().contains("6e400001", ignoreCase = true)
        } == true

        // Check if raw payload bytes contain "DeskBot" in ASCII
        val rawBytesMatch = scanRecord?.bytes?.let { bytes ->
            val deskBotPattern = "DeskBot".toByteArray(Charsets.US_ASCII)
            bytesContainsSubsequence(bytes, deskBotPattern)
        } == true

        val isDeskBotName = advertisedName?.contains("DeskBot", ignoreCase = true) == true
        val isDeskBot = isDeskBotName || hasDeskBotService || rawBytesMatch

        // Determine best display name
        val displayName = when {
            advertisedName != null -> advertisedName
            isDeskBot -> "DeskBot"
            else -> return // Ignore anonymous beacon spam with no name and no DeskBot service
        }

        runOnUiThread {
            val existingIndex = realDevicesList.indexOfFirst {
                it.address.equals(device.address, ignoreCase = true)
            }

            if (existingIndex >= 0) {
                val existing = realDevicesList[existingIndex]
                val bestName = if (existing.name == "DeskBot" || isDeskBot) {
                    if (advertisedName != null && advertisedName != "DeskBot") advertisedName else "DeskBot"
                } else if (advertisedName != null) {
                    advertisedName
                } else {
                    existing.name
                }
                val updatedDeskBot = existing.isDeskBot || isDeskBot
                if (bestName != existing.name || updatedDeskBot != existing.isDeskBot) {
                    realDevicesList[existingIndex] = existing.copy(
                        name = bestName,
                        isDeskBot = updatedDeskBot
                    )
                    devicesAdapter?.notifyDataSetChanged()
                }
            } else {
                val newItem = RealBluetoothDeviceItem(
                    name = displayName,
                    address = device.address,
                    isBonded = device.bondState == BluetoothDevice.BOND_BONDED,
                    device = device,
                    isDeskBot = isDeskBot
                )

                if (isDeskBot) {
                    // Insert before non-DeskBot devices so DeskBot is at the top
                    val firstNonDeskBotIndex = realDevicesList.indexOfFirst { !it.isDeskBot }
                    if (firstNonDeskBotIndex >= 0) {
                        realDevicesList.add(firstNonDeskBotIndex, newItem)
                    } else {
                        realDevicesList.add(newItem)
                    }
                } else {
                    realDevicesList.add(newItem)
                }
                devicesAdapter?.notifyDataSetChanged()
                sheetEmptyContainer?.visibility = View.GONE
            }
        }
    }

    private fun bytesContainsSubsequence(target: ByteArray, pattern: ByteArray): Boolean {
        if (pattern.isEmpty() || target.size < pattern.size) return false
        val maxIdx = target.size - pattern.size
        for (i in 0..maxIdx) {
            var match = true
            for (j in pattern.indices) {
                if (target[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    enum class ConnectionState {
        IDLE,
        SCANNING,
        CONNECTING,
        CONNECTED,
        UPDATING
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        setContent {
            DeskBotTheme {
                DeskBotMainScreen(
                    state = uiState.value,
                    onConnectClick = { handleConnectAction() },
                    onQuickScanClick = { handleQuickScanAction() },
                    onCheckUpdatesClick = { handleCheckUpdatesAction() },
                    onInstallUpdateClick = { handleInstallUpdateAction() },
                    onStatusClick = { handleStatusAction() },
                    onToggleTerminal = { handleToggleTerminal() },
                    onToggleAiSession = { handleToggleAiSession() }
                )
            }
        }
    }

    private fun handleConnectAction() {
        triggerHapticFeedback()
        if (isConnecting) return

        if (!isConnected) {
            if (!hasBluetoothPermissions()) {
                requestPermissionLauncher.launch(getRequiredBluetoothPermissions())
            } else {
                checkBluetoothAndShowDevices()
            }
        } else {
            disconnectDevice()
        }
    }

    private fun handleQuickScanAction() {
        triggerHapticFeedback()
        if (!hasBluetoothPermissions()) {
            requestPermissionLauncher.launch(getRequiredBluetoothPermissions())
        } else {
            checkBluetoothAndShowDevices()
        }
    }

    private fun handleCheckUpdatesAction() {
        triggerHapticFeedback()
        if (!isConnected) {
            Toast.makeText(this, "Connect to DeskBot via Bluetooth first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isUpdateAvailable) {
            val targetVersion = latestVersionTag ?: "new version"
            startBleFirmwareUpdate(targetVersion)
        } else if (!isUpdateCheckActive) {
            checkForUpdates()
        }
    }

    private fun handleInstallUpdateAction() {
        triggerHapticFeedback()
        if (!isConnected) {
            Toast.makeText(this, "Connect to DeskBot via Bluetooth first.", Toast.LENGTH_SHORT).show()
            return
        }
        val targetVersion = latestVersionTag ?: "new version"
        startBleFirmwareUpdate(targetVersion)
    }

    private fun handleStatusAction() {
        triggerHapticFeedback()
        val statusMsg = if (isConnected) {
            "DeskBot connected (MTU $negotiatedMtu B)"
        } else {
            "DeskBot is currently offline"
        }
        Toast.makeText(this, statusMsg, Toast.LENGTH_SHORT).show()
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (_: Exception) {}
    }

    private fun updateConnectionState(state: ConnectionState, extraText: String? = null) {
        val label = when (state) {
            ConnectionState.IDLE -> "DeskBot · Disconnected"
            ConnectionState.SCANNING -> "Scanning for DeskBot…"
            ConnectionState.CONNECTING -> "Pairing with DeskBot…"
            ConnectionState.CONNECTED -> extraText ?: "DeskBot · Connected"
            ConnectionState.UPDATING -> extraText ?: "Updating Firmware…"
        }
        uiState.value = uiState.value.copy(
            connectionState = state,
            connectionText = label
        )
    }

    private fun checkBluetoothAndShowDevices() {
        val adapter = bluetoothAdapter ?: (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        bluetoothAdapter = adapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device.", Toast.LENGTH_LONG).show()
            return
        }

        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        showDevicePickerSheet()
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedBleDevices() {
        if (!hasBluetoothPermissions()) return
        val adapter = bluetoothAdapter ?: (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        bluetoothAdapter = adapter
        val bonded = adapter?.bondedDevices ?: return

        for (device in bonded) {
            // STRICT REQUIREMENT: Only BLE devices. Exclude Bluetooth Classic (BR/EDR).
            if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                continue
            }
            val devName = device.name?.takeIf { it.isNotBlank() } ?: "Paired BLE Device"
            val isDeskBot = devName.contains("DeskBot", ignoreCase = true)
            val item = RealBluetoothDeviceItem(
                name = devName,
                address = device.address,
                isBonded = true,
                device = device,
                isDeskBot = isDeskBot
            )

            val existingIndex = realDevicesList.indexOfFirst {
                it.address.equals(device.address, ignoreCase = true)
            }
            if (existingIndex >= 0) {
                realDevicesList[existingIndex] = item
            } else {
                if (isDeskBot) {
                    realDevicesList.add(0, item) // Paired DeskBot at top of list
                } else {
                    realDevicesList.add(item)
                }
            }
        }
    }

    private fun isLocationServicesEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePickerSheet() {
        val sheetView = layoutInflater.inflate(R.layout.popup_bluetooth_devices, null)
        val dialog = BottomSheetDialog(this, R.style.FlatBottomSheetDialogTheme)
        dialog.setContentView(sheetView)
        deviceBottomSheetDialog = dialog

        val listView = sheetView.findViewById<ListView>(R.id.deviceListView)
        val btnScan = sheetView.findViewById<MaterialButton>(R.id.btnScanDevices)
        sheetScanProgressBar = sheetView.findViewById(R.id.scanProgressBar)
        sheetEmptyContainer = sheetView.findViewById(R.id.emptyDevicesContainer)

        realDevicesList.clear()

        // Populate previously paired (bonded) BLE devices immediately
        loadPairedBleDevices()

        devicesAdapter = object : ArrayAdapter<RealBluetoothDeviceItem>(
            this,
            R.layout.item_bluetooth_device,
            realDevicesList
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_bluetooth_device, parent, false)
                val item = getItem(position) ?: return view
                view.findViewById<TextView>(R.id.deviceName).text = item.name
                view.findViewById<TextView>(R.id.deviceAddress).text = item.address

                val statusBadge = view.findViewById<TextView>(R.id.deviceStatus)
                when {
                    item.isDeskBot && item.isBonded -> {
                        statusBadge.text = "PAIRED · DESKBOT"
                        statusBadge.setTextColor(ContextCompat.getColor(context, R.color.catppuccin_mauve))
                        statusBadge.backgroundTintList = ColorStateList.valueOf(0x26CBA6F7.toInt())
                    }
                    item.isDeskBot -> {
                        statusBadge.text = "DESKBOT"
                        statusBadge.setTextColor(ContextCompat.getColor(context, R.color.catppuccin_mauve))
                        statusBadge.backgroundTintList = ColorStateList.valueOf(0x26CBA6F7.toInt())
                    }
                    item.isBonded -> {
                        statusBadge.text = "PAIRED"
                        statusBadge.setTextColor(ContextCompat.getColor(context, R.color.catppuccin_blue))
                        statusBadge.backgroundTintList = ColorStateList.valueOf(0x2689B4FA.toInt())
                    }
                    else -> {
                        statusBadge.text = "BLE"
                        statusBadge.setTextColor(ContextCompat.getColor(context, R.color.catppuccin_green))
                        statusBadge.backgroundTintList = ColorStateList.valueOf(0x26A6E3A1.toInt())
                    }
                }
                return view
            }
        }

        listView.adapter = devicesAdapter

        if (realDevicesList.isEmpty()) {
            sheetEmptyContainer?.visibility = View.VISIBLE
        } else {
            sheetEmptyContainer?.visibility = View.GONE
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = realDevicesList[position]
            dialog.dismiss()
            stopBluetoothScan()
            connectToRealDevice(selected)
        }

        btnScan.setOnClickListener {
            triggerHapticFeedback()
            loadPairedBleDevices()
            devicesAdapter?.notifyDataSetChanged()
            startBluetoothScan()
        }

        dialog.setOnDismissListener {
            stopBluetoothScan()
            if (!isConnected && !isConnecting) {
                updateConnectionState(ConnectionState.IDLE)
            }
        }

        dialog.show()
        startBluetoothScan()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        if (!hasBluetoothPermissions()) return

        // Always re-acquire adapter and scanner in case Bluetooth was recently enabled
        val adapter = bluetoothAdapter ?: (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        bluetoothAdapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationServicesEnabled()) {
            Toast.makeText(this, "Please turn on Location to discover nearby BLE devices.", Toast.LENGTH_LONG).show()
        }

        val scanner = adapter.bluetoothLeScanner
        bluetoothLeScanner = scanner
        if (scanner == null) {
            Toast.makeText(this, "Bluetooth LE Scanner is unavailable.", Toast.LENGTH_SHORT).show()
            return
        }

        sheetScanProgressBar?.visibility = View.VISIBLE
        updateConnectionState(ConnectionState.SCANNING)

        try {
            scanner.stopScan(bleScanCallback)
        } catch (_: Exception) {}

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            scanner.startScan(null, scanSettings, bleScanCallback)
        } catch (e: Exception) {
            sheetScanProgressBar?.visibility = View.GONE
            updateConnectionState(ConnectionState.IDLE)
            Toast.makeText(this, "Failed to start BLE scan: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        if (hasBluetoothPermissions()) {
            try {
                bluetoothLeScanner?.stopScan(bleScanCallback)
            } catch (_: Exception) {}
        }
        sheetScanProgressBar?.visibility = View.GONE
    }

    @SuppressLint("MissingPermission")
    private fun connectToRealDevice(item: RealBluetoothDeviceItem) {
        val dev = item.device
        isConnecting = true
        updateConnectionState(ConnectionState.CONNECTING)

        uiState.value = uiState.value.copy(
            isConnecting = true,
            deviceName = item.name
        )

        stopBluetoothScan()
        activeGatt?.close()

        activeGatt = dev.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread { handleBluetoothConnectionLost() }
                } else {
                    runOnUiThread {
                        isConnecting = false
                        resetToDisconnectedUI()
                        showConnectionErrorNotification(item.name, "BLE connection error ($status)")
                    }
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(BLE_SERVICE_UUID)
                val rx = service?.getCharacteristic(BLE_RX_UUID)
                val firmware = service?.getCharacteristic(BLE_FIRMWARE_UUID)
                val statusChar = service?.getCharacteristic(BLE_STATUS_UUID)
                val audioChar = service?.getCharacteristic(BLE_AUDIO_UUID)
                val displayChar = service?.getCharacteristic(BLE_DISPLAY_UUID)

                commandCharacteristic = rx
                firmwareCharacteristic = firmware
                audioCharacteristic = audioChar
                displayCharacteristic = displayChar

                if (audioChar != null) TerminalLog.log("BLE", "Audio characteristic found", LogLevel.SUCCESS)
                if (displayChar != null) TerminalLog.log("BLE", "Display characteristic found", LogLevel.SUCCESS)

                // Enable local Android notifications immediately for statusChar
                if (statusChar != null) {
                    gatt.setCharacteristicNotification(statusChar, true)
                    val descriptor = statusChar.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }

                // Schedule audioChar CCCD descriptor write on main looper after 500ms
                if (audioChar != null) {
                    gatt.setCharacteristicNotification(audioChar, true)
                    android.os.Handler(mainLooper).postDelayed({
                        val descriptor = audioChar.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                            TerminalLog.log("BLE", "Subscribed to Audio stream notifications", LogLevel.SUCCESS)
                        }
                    }, 500)
                }

                // Request MTU after descriptor writes complete
                android.os.Handler(mainLooper).postDelayed({
                    gatt.requestMtu(247)
                }, 900)

                runOnUiThread {
                    onDeskBotSuccessfullyConnected(item.name)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                val charUuid = descriptor.characteristic.uuid
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    TerminalLog.log("BLE", "Notifications enabled for $charUuid", LogLevel.SUCCESS)
                } else {
                    TerminalLog.log("BLE", "Failed to enable notifications for $charUuid (status=$status)", LogLevel.ERROR)
                }

                // STEP 3 of sequential GATT setup: Once BLE_STATUS_UUID CCCD write finishes,
                // enable notifications on BLE_AUDIO_UUID, then auto-start the Gemini Live session.
                if (charUuid == BLE_STATUS_UUID) {
                    val audioChar = audioCharacteristic
                    if (audioChar != null) {
                        enableNotification(gatt, audioChar)
                    }
                } else if (charUuid == BLE_AUDIO_UUID) {
                    runOnUiThread {
                        if (!isAiSessionActive) {
                            startAiSession()
                        }
                    }
                }
            }


            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == BLE_AUDIO_UUID) {
                    // Check if the packet is a VOICE_START / VOICE_END control marker sent over audioChar
                    if (value.size <= 16) {
                        val marker = String(value, Charsets.UTF_8).trim()
                        if (marker.contains("VOICE_START", ignoreCase = true)) {
                            TerminalLog.log("AUDIO", "Voice input started from ESP32", LogLevel.SUCCESS)
                            return
                        } else if (marker.contains("VOICE_END", ignoreCase = true)) {
                            TerminalLog.log("AUDIO", "Voice input ended – sending end of turn", LogLevel.INFO)
                            geminiManager?.sendEndOfTurn()
                            return
                        }
                    }

                    TerminalLog.log("AUDIO", "Received ${value.size} bytes of audio data", LogLevel.INFO)
                    if (!isAiSessionActive || geminiManager == null) {
                        TerminalLog.log("AUDIO", "AI session not started – tap 'Start' in Voice AI", LogLevel.WARN)
                        return
                    }
                    geminiManager?.sendAudio(value)
                } else if (characteristic.uuid == BLE_STATUS_UUID) {
                    val statusText = String(value, Charsets.UTF_8).trim()
                    TerminalLog.log("BLE", "Status: $statusText", LogLevel.INFO)

                    if (statusText.contains("VOICE_START", ignoreCase = true)) {
                        TerminalLog.log("AUDIO", "Voice input started from ESP32", LogLevel.SUCCESS)
                    } else if (statusText.contains("VOICE_END", ignoreCase = true)) {
                        TerminalLog.log("AUDIO", "Voice input ended – sending end of turn", LogLevel.INFO)
                        geminiManager?.sendEndOfTurn()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                onCharacteristicChanged(gatt, characteristic, value)
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    negotiatedMtu = mtu
                    TerminalLog.log("BLE", "Negotiated MTU: $mtu bytes", LogLevel.SUCCESS)
                    runOnUiThread {
                        uiState.value = uiState.value.copy(telemetryMtu = "$mtu B")
                    }
                }
            }
            private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                synchronized(gattWriteLock) {
                    pendingWriteSucceeded = status == BluetoothGatt.GATT_SUCCESS
                    pendingWriteLatch?.countDown()
                }
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Triggered on successful Bluetooth connection!
     * Updates telemetry and state in flat Catppuccin theme.
     */
    private fun onDeskBotSuccessfullyConnected(deviceName: String) {
        isConnected = true
        isConnecting = false

        TerminalLog.log("BLE", "Connected to $deviceName (MTU $negotiatedMtu B)", LogLevel.SUCCESS)
        TerminalLog.log("SYSTEM", "DeskBot link established — ready for commands", LogLevel.SUCCESS)

        uiState.value = uiState.value.copy(
            isConnected = true,
            isConnecting = false,
            deviceName = deviceName,
            telemetryStatus = "Online",
            telemetryRadio = "BLE 5.0",
            telemetryMtu = "$negotiatedMtu B",
            connectionState = ConnectionState.CONNECTED,
            connectionText = "$deviceName Connected"
        )

        Toast.makeText(this, "Connected to $deviceName!", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        stopAiSession()
        val name = connectedDevice?.name ?: "DeskBot"
        try {
            activeGatt?.close()
        } catch (_: Exception) {}
        activeGatt = null
        commandCharacteristic = null
        firmwareCharacteristic = null
        audioCharacteristic = null
        displayCharacteristic = null
        connectedDevice = null
        isConnected = false
        isConnecting = false

        TerminalLog.log("BLE", "Disconnected from $name", LogLevel.INFO)
        resetToDisconnectedUI()
        Toast.makeText(this, "Disconnected from $name", Toast.LENGTH_SHORT).show()
    }

    private fun resetToDisconnectedUI() {
        isConnected = false
        isConnecting = false

        updateConnectionState(ConnectionState.IDLE)
        uiState.value = uiState.value.copy(
            isConnected = false,
            isConnecting = false,
            deviceName = "DeskBot ESP32",
            telemetryStatus = "Offline",
            telemetryMtu = "23 B",
            isUpdateAvailable = false,
            isOtaInProgress = false,
            updateStatus = "Check for Updates"
        )
    }

    private fun handleBluetoothConnectionLost() {
        if (!isConnected && activeGatt == null && connectedDevice == null) return

        stopAiSession()
        @SuppressLint("MissingPermission")
        val devName = connectedDevice?.name ?: "DeskBot"
        try {
            activeGatt?.close()
        } catch (_: Exception) {}
        activeGatt = null
        commandCharacteristic = null
        firmwareCharacteristic = null
        audioCharacteristic = null
        displayCharacteristic = null
        connectedDevice = null

        TerminalLog.log("BLE", "Connection lost with $devName", LogLevel.WARN)
        if (isConnected) {
            Toast.makeText(this, "Connection lost with $devName", Toast.LENGTH_SHORT).show()
            resetToDisconnectedUI()
        }
    }

    // --- GitHub Firmware Updates & BLE OTA ---

    private fun checkForUpdates() {
        isUpdateCheckActive = true
        uiState.value = uiState.value.copy(
            updateStatus = "Checking GitHub Releases…"
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/ThenulSahansa/DeskBot/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "DeskBot-Android-App")
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)

                    val latestTagName = json.optString("tag_name", "").trim()
                    val releaseBody = json.optString("body", "").trim()
                    val publishedAt = json.optString("published_at", "").trim()

                    val formattedDate = parsePublishedDate(publishedAt)
                    val currentVersionTag = uiState.value.currentVersion.trim()
                    val hasHigherVersion = latestTagName.isNotEmpty() && isHigherVersion(latestTagName, currentVersionTag)

                    withContext(Dispatchers.Main) {
                        isUpdateCheckActive = false

                        if (hasHigherVersion) {
                            latestVersionTag = latestTagName
                            isUpdateAvailable = true
                            uiState.value = uiState.value.copy(
                                isUpdateAvailable = true,
                                latestVersionTag = latestTagName,
                                releaseDate = formattedDate,
                                releaseNotes = if (releaseBody.isNotEmpty()) releaseBody else "Bug fixes and performance improvements for DeskBot.",
                                updateStatus = "Update Available"
                            )
                        } else {
                            uiState.value = uiState.value.copy(
                                isUpdateAvailable = false,
                                updateStatus = "DeskBot is up to date"
                            )
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isUpdateCheckActive = false
                        uiState.value = uiState.value.copy(
                            updateStatus = "Check failed ($responseCode)"
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isUpdateCheckActive = false
                    uiState.value = uiState.value.copy(
                        updateStatus = "Network error"
                    )
                }
            }
        }
    }

    private fun parsePublishedDate(publishedAt: String): String {
        if (publishedAt.isEmpty()) return "Recently"
        return try {
            publishedAt.split("T").firstOrNull() ?: publishedAt
        } catch (_: Exception) {
            publishedAt
        }
    }

    private fun isHigherVersion(latestTag: String, currentTag: String): Boolean {
        val cleanLatest = latestTag.replace(Regex("[^0-9.]"), "")
        val cleanCurrent = currentTag.replace(Regex("[^0-9.]"), "")

        val latestParts = cleanLatest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = cleanCurrent.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLength = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val latestPart = latestParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }

            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    private fun startBleFirmwareUpdate(targetVersion: String) {
        isFirmwareUpdateInProgress = true
        uiState.value = uiState.value.copy(
            isOtaInProgress = true,
            otaPercentage = 0,
            otaStatusLabel = "Flashing firmware over BLE…"
        )
        updateConnectionState(ConnectionState.UPDATING, "Updating DeskBot 0%")

        lifecycleScope.launch(Dispatchers.IO) {
            var firmware: File? = null
            try {
                firmware = downloadFirmware()
                val downloadedFirmware = requireNotNull(firmware)
                val gatt = activeGatt ?: error("DeskBot disconnected")
                val command = commandCharacteristic ?: error("DeskBot OTA command channel is unavailable")
                val data = firmwareCharacteristic ?: error("DeskBot OTA data channel is unavailable")

                check(writeCharacteristicBlocking(gatt, command, "OTA_BEGIN:${downloadedFirmware.length()}".toByteArray())) {
                    "DeskBot rejected the update request"
                }

                val chunkSize = min(240, (negotiatedMtu - 3).coerceAtLeast(20))
                var sent = 0L
                BufferedInputStream(downloadedFirmware.inputStream()).use { input ->
                    val buffer = ByteArray(chunkSize)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        check(writeCharacteristicBlocking(gatt, data, buffer.copyOf(count))) {
                            "Bluetooth transfer failed"
                        }
                        sent += count
                        val percent = (sent * 100 / downloadedFirmware.length()).toInt()
                        withContext(Dispatchers.Main) {
                            uiState.value = uiState.value.copy(
                                otaPercentage = percent
                            )
                            updateConnectionState(ConnectionState.UPDATING, "Updating DeskBot $percent%")
                        }
                    }
                }
                check(writeCharacteristicBlocking(gatt, command, "OTA_END".toByteArray())) {
                    "DeskBot could not finalize the update"
                }

                withContext(Dispatchers.Main) {
                    uiState.value = uiState.value.copy(
                        currentVersion = targetVersion,
                        updateStatus = "DeskBot is up to date",
                        isOtaInProgress = false,
                        isUpdateAvailable = false
                    )
                    updateConnectionState(ConnectionState.CONNECTED, "DeskBot Updated!")
                    Toast.makeText(this@MainActivity, "Update successful. DeskBot is restarting.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                activeGatt?.let { gatt ->
                    commandCharacteristic?.let { command ->
                        writeCharacteristicBlocking(gatt, command, "OTA_ABORT".toByteArray())
                    }
                }
                withContext(Dispatchers.Main) {
                    uiState.value = uiState.value.copy(
                        isOtaInProgress = false,
                        updateStatus = "Update failed: ${e.message}"
                    )
                    Toast.makeText(this@MainActivity, e.message ?: "Firmware update failed", Toast.LENGTH_LONG).show()
                }
            } finally {
                isFirmwareUpdateInProgress = false
                firmware?.delete()
            }
        }
    }

    private fun downloadFirmware(): File {
        val url = URL("https://github.com/ThenulSahansa/DeskBot/releases/latest/download/firmware.ino.bin")
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "DeskBot-Android-App")
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        check(connection.responseCode == HttpURLConnection.HTTP_OK) {
            "Firmware download failed (${connection.responseCode})"
        }

        return File.createTempFile("deskbot-firmware-", ".bin", cacheDir).also { file ->
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            check(file.length() > 0) { "Downloaded firmware is empty" }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicBlocking(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        val latch = CountDownLatch(1)
        synchronized(gattWriteLock) {
            pendingWriteLatch = latch
            pendingWriteSucceeded = false
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            if (!accepted) {
                pendingWriteLatch = null
                return false
            }
        }
        val completed = latch.await(15, TimeUnit.SECONDS)
        synchronized(gattWriteLock) {
            val successful = completed && pendingWriteSucceeded
            pendingWriteLatch = null
            return successful
        }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return getRequiredBluetoothPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showConnectionErrorNotification(deviceName: String, errorDetails: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "bt_connection_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Bluetooth Connection Status",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for Bluetooth connection status and failures"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle("Bluetooth Connection Failed")
            .setContentText("Could not connect to $deviceName")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Failed to connect to $deviceName.\nReason: $errorDetails"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            notificationManager.notify(1001, builder.build())
        } catch (_: SecurityException) {}
    }

    private fun handleToggleTerminal() {
        triggerHapticFeedback()
        val newState = !uiState.value.isTerminalVisible
        uiState.value = uiState.value.copy(isTerminalVisible = newState)
        TerminalLog.log("SYSTEM", "Terminal visibility changed: ${if (newState) "shown" else "hidden"}", LogLevel.INFO)
    }

    private fun handleToggleAiSession() {
        triggerHapticFeedback()
        if (isAiSessionActive) {
            stopAiSession()
        } else {
            startAiSession()
        }
    }

    private fun startAiSession() {
        if (GeminiLiveManager.API_KEY == "YOUR_GEMINI_API_KEY") {
            Toast.makeText(this, "Please configure your Gemini API Key in GeminiLiveManager.kt", Toast.LENGTH_LONG).show()
            TerminalLog.log("GEMINI", "Warning: API key is set to default placeholder in GeminiLiveManager.kt", LogLevel.WARN)
        }

        TerminalLog.log("GEMINI", "Starting AI Voice Assistant session…", LogLevel.INFO)
        isAiSessionActive = true
        uiState.value = uiState.value.copy(isAiSessionActive = true)

        geminiManager?.disconnect()
        geminiManager = GeminiLiveManager(
            onResponse = { responseText ->
                runOnUiThread {
                    uiState.value = uiState.value.copy(lastGeminiResponse = responseText)
                }
                sendTextToDisplay(responseText)
            },
            onSetupComplete = {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Gemini Live Ready!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { errorMsg ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Gemini Error: $errorMsg", Toast.LENGTH_SHORT).show()
                    stopAiSession()
                }
            }
        )
        geminiManager?.connect()
    }

    private fun stopAiSession() {
        if (!isAiSessionActive && geminiManager == null) return
        TerminalLog.log("GEMINI", "Stopping AI Voice Assistant session", LogLevel.INFO)
        geminiManager?.disconnect()
        geminiManager = null
        isAiSessionActive = false
        uiState.value = uiState.value.copy(isAiSessionActive = false)
    }

    private fun sendTextToDisplay(text: String) {
        val gatt = activeGatt
        val displayChar = displayCharacteristic
        if (gatt == null || displayChar == null) {
            TerminalLog.log("DISPLAY", "Cannot send text: DeskBot not connected or display characteristic unavailable", LogLevel.WARN)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Suffix with DISPLAY_END marker so firmware knows the message is complete
                val messageWithEndMarker = "$text\nDISPLAY_END"
                val bytes = messageWithEndMarker.toByteArray(Charsets.UTF_8)
                val chunkSize = min(240, (negotiatedMtu - 3).coerceAtLeast(20))
                var offset = 0
                while (offset < bytes.size) {
                    val end = min(offset + chunkSize, bytes.size)
                    val chunk = bytes.copyOfRange(offset, end)
                    val success = writeCharacteristicBlocking(gatt, displayChar, chunk)
                    if (!success) {
                        TerminalLog.log("DISPLAY", "Failed to write chunk to OLED display", LogLevel.ERROR)
                        break
                    }
                    offset = end
                }
                TerminalLog.log("DISPLAY", "Sent response to DeskBot OLED (${bytes.size} B)", LogLevel.SUCCESS)
            } catch (e: Exception) {
                TerminalLog.log("DISPLAY", "Error sending text to OLED: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAiSession()
        try {
            stopBluetoothScan()
            activeGatt?.close()
        } catch (_: Exception) {}
    }
}