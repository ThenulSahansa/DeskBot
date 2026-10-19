package com.example.deskbot

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
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
    val device: BluetoothDevice
)

class MainActivity : AppCompatActivity() {

    private var isConnected = false
    private var isConnecting = false
    private var isAnimating = false

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

    // UI references
    private lateinit var layoutDynamicIsland: LinearLayout
    private lateinit var ivIslandIcon: ImageView
    private lateinit var tvIslandTitle: TextView
    private lateinit var viewIslandDot: View
    private lateinit var islandProgressBar: ProgressBar

    private lateinit var btnQuickScan: MaterialButton
    private lateinit var btnMainAction: MaterialButton
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroSubtitle: TextView
    private lateinit var ivHeroRobot: ImageView
    private lateinit var layoutOrbContainer: View

    private lateinit var tvTelemetryStatus: TextView
    private lateinit var tvTelemetryRadio: TextView
    private lateinit var tvTelemetryMtu: TextView

    private lateinit var tvCurrentVersion: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var btnCheckUpdates: MaterialButton

    private lateinit var layoutOtaProgress: LinearLayout
    private lateinit var tvOtaStatusLabel: TextView
    private lateinit var tvOtaPercentage: TextView
    private lateinit var progressBarOta: ProgressBar

    private lateinit var layoutReleaseNotesCard: LinearLayout
    private lateinit var tvReleaseNotesTitle: TextView
    private lateinit var tvReleaseNotesDate: TextView
    private lateinit var tvReleaseNotesContent: TextView
    private lateinit var btnInstallUpdateNow: MaterialButton

    private lateinit var nameDropView: IosNameDropView

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
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Spring Interpolators
    private val iosSpringOvershoot = PathInterpolator(0.34f, 1.56f, 0.64f, 1.0f)
    private val iosEaseInOut = PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)

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

    // Strict BLE Scan Callback
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val advertisedName = device.name ?: result.scanRecord?.deviceName
            val isDeskBotService = result.scanRecord?.serviceUuids?.any {
                it.uuid == BLE_SERVICE_UUID
            } == true

            val name = advertisedName ?: if (isDeskBotService) "DeskBot" else null
            if (name != null && realDevicesList.none { it.address == device.address }) {
                realDevicesList.add(RealBluetoothDeviceItem(name, device.address, device.bondState == BluetoothDevice.BOND_BONDED, device))
                devicesAdapter?.notifyDataSetChanged()
                sheetEmptyContainer?.visibility = View.GONE
            }
        }

        override fun onScanFailed(errorCode: Int) {
            sheetScanProgressBar?.visibility = View.GONE
        }
    }

    // Disconnection Broadcast Receiver
    private val bluetoothDisconnectReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    if (connectedDevice != null && (device == null || device.address == connectedDevice?.address)) {
                        handleBluetoothConnectionLost()
                    }
                }
            }
        }
    }

    enum class IslandState {
        IDLE,
        SCANNING,
        CONNECTING,
        CONNECTED,
        UPDATING
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Perfect edge-to-edge window insets handling for all mobile phone screens
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.safeContentContainer)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top + (4 * resources.displayMetrics.density).toInt(),
                v.paddingRight,
                systemBars.bottom + (8 * resources.displayMetrics.density).toInt()
            )
            insets
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        // Register ACL disconnection broadcast receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
        }
        registerReceiver(bluetoothDisconnectReceiver, filter)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        layoutDynamicIsland = findViewById(R.id.layoutDynamicIsland)
        ivIslandIcon = findViewById(R.id.ivIslandIcon)
        tvIslandTitle = findViewById(R.id.tvIslandTitle)
        viewIslandDot = findViewById(R.id.viewIslandDot)
        islandProgressBar = findViewById(R.id.islandProgressBar)

        btnQuickScan = findViewById(R.id.btnQuickScan)
        btnMainAction = findViewById(R.id.btnMainAction)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroSubtitle = findViewById(R.id.tvHeroSubtitle)
        ivHeroRobot = findViewById(R.id.ivHeroRobot)
        layoutOrbContainer = findViewById(R.id.layoutOrbContainer)

        tvTelemetryStatus = findViewById(R.id.tvTelemetryStatus)
        tvTelemetryRadio = findViewById(R.id.tvTelemetryRadio)
        tvTelemetryMtu = findViewById(R.id.tvTelemetryMtu)

        tvCurrentVersion = findViewById(R.id.tvCurrentVersion)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)

        layoutOtaProgress = findViewById(R.id.layoutOtaProgress)
        tvOtaStatusLabel = findViewById(R.id.tvOtaStatusLabel)
        tvOtaPercentage = findViewById(R.id.tvOtaPercentage)
        progressBarOta = findViewById(R.id.progressBarOta)

        layoutReleaseNotesCard = findViewById(R.id.layoutReleaseNotesCard)
        tvReleaseNotesTitle = findViewById(R.id.tvReleaseNotesTitle)
        tvReleaseNotesDate = findViewById(R.id.tvReleaseNotesDate)
        tvReleaseNotesContent = findViewById(R.id.tvReleaseNotesContent)
        btnInstallUpdateNow = findViewById(R.id.btnInstallUpdateNow)

        nameDropView = findViewById(R.id.nameDropView)
    }

    private fun setupListeners() {
        btnMainAction.setOnClickListener {
            triggerHapticFeedback()
            if (isConnecting || isAnimating) return@setOnClickListener

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

        btnQuickScan.setOnClickListener {
            triggerHapticFeedback()
            if (!hasBluetoothPermissions()) {
                requestPermissionLauncher.launch(getRequiredBluetoothPermissions())
            } else {
                checkBluetoothAndShowDevices()
            }
        }

        btnCheckUpdates.setOnClickListener {
            triggerHapticFeedback()
            if (!isConnected) {
                Toast.makeText(this, "Connect to DeskBot via Bluetooth first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isUpdateAvailable) {
                val targetVersion = latestVersionTag ?: "new version"
                startBleFirmwareUpdate(targetVersion)
            } else if (!isUpdateCheckActive) {
                checkForUpdates()
            }
        }

        btnInstallUpdateNow.setOnClickListener {
            triggerHapticFeedback()
            if (!isConnected) {
                Toast.makeText(this, "Connect to DeskBot via Bluetooth first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val targetVersion = latestVersionTag ?: "new version"
            startBleFirmwareUpdate(targetVersion)
        }

        layoutDynamicIsland.setOnClickListener {
            triggerHapticFeedback()
            val statusMsg = if (isConnected) {
                "DeskBot is connected (${connectedDevice?.name ?: "BLE Device"})"
            } else {
                "DeskBot is disconnected"
            }
            Toast.makeText(this, statusMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (_: Exception) {}
    }

    private fun updateIslandState(state: IslandState, extraText: String? = null) {
        when (state) {
            IslandState.IDLE -> {
                tvIslandTitle.text = getString(R.string.island_idle)
                ivIslandIcon.setImageResource(R.drawable.ic_ios_bluetooth)
                ivIslandIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ios_blue))
                viewIslandDot.visibility = View.VISIBLE
                viewIslandDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ios_label_tertiary))
                islandProgressBar.visibility = View.GONE
            }
            IslandState.SCANNING -> {
                tvIslandTitle.text = getString(R.string.island_scanning)
                ivIslandIcon.setImageResource(R.drawable.ic_ios_antenna)
                ivIslandIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ios_cyan))
                viewIslandDot.visibility = View.GONE
                islandProgressBar.visibility = View.VISIBLE
            }
            IslandState.CONNECTING -> {
                tvIslandTitle.text = getString(R.string.island_connecting)
                ivIslandIcon.setImageResource(R.drawable.ic_ios_bluetooth)
                ivIslandIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ios_cyan))
                viewIslandDot.visibility = View.GONE
                islandProgressBar.visibility = View.VISIBLE
            }
            IslandState.CONNECTED -> {
                tvIslandTitle.text = extraText ?: getString(R.string.island_connected)
                ivIslandIcon.setImageResource(R.drawable.ic_ios_check_circle)
                ivIslandIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ios_green))
                viewIslandDot.visibility = View.VISIBLE
                viewIslandDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ios_green))
                islandProgressBar.visibility = View.GONE
            }
            IslandState.UPDATING -> {
                tvIslandTitle.text = extraText ?: getString(R.string.island_updating)
                ivIslandIcon.setImageResource(R.drawable.ic_ios_arrow_clockwise)
                ivIslandIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ios_cyan))
                viewIslandDot.visibility = View.GONE
                islandProgressBar.visibility = View.VISIBLE
            }
        }

        // Animate island bounce
        layoutDynamicIsland.scaleX = 0.95f
        layoutDynamicIsland.scaleY = 0.95f
        layoutDynamicIsland.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(300)
            .setInterpolator(iosSpringOvershoot)
            .start()
    }

    private fun checkBluetoothAndShowDevices() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device.", Toast.LENGTH_LONG).show()
            return
        }

        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        showIosDevicePickerSheet()
    }

    @SuppressLint("MissingPermission")
    private fun showIosDevicePickerSheet() {
        val sheetView = layoutInflater.inflate(R.layout.popup_bluetooth_devices, null)
        val dialog = BottomSheetDialog(this, R.style.IosBottomSheetDialogTheme)
        dialog.setContentView(sheetView)
        deviceBottomSheetDialog = dialog

        val listView = sheetView.findViewById<ListView>(R.id.deviceListView)
        val btnScan = sheetView.findViewById<MaterialButton>(R.id.btnScanDevices)
        sheetScanProgressBar = sheetView.findViewById(R.id.scanProgressBar)
        sheetEmptyContainer = sheetView.findViewById(R.id.emptyDevicesContainer)

        realDevicesList.clear()

        // 1. Load paired BLE devices from Android settings
        val bondedDevices = bluetoothAdapter?.bondedDevices
        bondedDevices?.forEach { dev ->
            val isLeOrDual = dev.type == BluetoothDevice.DEVICE_TYPE_LE || dev.type == BluetoothDevice.DEVICE_TYPE_DUAL || dev.type == BluetoothDevice.DEVICE_TYPE_UNKNOWN
            if (isLeOrDual) {
                val name = dev.name ?: "Paired Device (${dev.address.takeLast(5)})"
                if (realDevicesList.none { it.address == dev.address }) {
                    realDevicesList.add(RealBluetoothDeviceItem(name, dev.address, isBonded = true, device = dev))
                }
            }
        }

        devicesAdapter = object : ArrayAdapter<RealBluetoothDeviceItem>(
            this,
            R.layout.item_bluetooth_device,
            realDevicesList
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_bluetooth_device, parent, false)
                val item = getItem(position)
                view.findViewById<TextView>(R.id.deviceName).text = item?.name
                view.findViewById<TextView>(R.id.deviceAddress).text = item?.address
                view.findViewById<TextView>(R.id.deviceStatus).text = if (item?.isBonded == true) "PAIRED" else "BLE"
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
            startBluetoothScan()
        }

        dialog.setOnDismissListener {
            stopBluetoothScan()
            if (!isConnected && !isConnecting) {
                updateIslandState(IslandState.IDLE)
            }
        }

        dialog.show()
        startBluetoothScan()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        if (!hasBluetoothPermissions()) return
        sheetScanProgressBar?.visibility = View.VISIBLE
        updateIslandState(IslandState.SCANNING)

        // Strict BLE scanning
        bluetoothLeScanner?.startScan(bleScanCallback)
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
        updateIslandState(IslandState.CONNECTING)

        btnMainAction.text = getString(R.string.connecting)
        btnMainAction.setIconResource(R.drawable.ic_ios_arrow_clockwise)
        tvHeroSubtitle.text = "Establishing secure link with ${item.name}…"

        stopBluetoothScan()

        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (_: Exception) {}
        activeGatt = null

        val gattCallback = object : BluetoothGattCallback() {
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

                runOnUiThread {
                    isConnecting = false
                    if (status == BluetoothGatt.GATT_SUCCESS && (rx != null || service != null)) {
                        activeGatt = gatt
                        commandCharacteristic = rx
                        firmwareCharacteristic = firmware
                        connectedDevice = dev
                        gatt.requestMtu(247)

                        tvTelemetryRadio.text = "BLE 5.0"
                        tvTelemetryRadio.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ios_cyan))
                        onDeskBotSuccessfullyConnected(item.name)
                    } else {
                        resetToDisconnectedUI()
                        showConnectionErrorNotification(item.name, "DeskBot BLE service was not found")
                        gatt.disconnect()
                        gatt.close()
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == BLE_STATUS_UUID) {
                    val statusText = String(value, Charsets.UTF_8).trim()
                    runOnUiThread {
                        if (statusText.contains("Complete", ignoreCase = true) || statusText.contains("Restarting", ignoreCase = true)) {
                            tvOtaStatusLabel.text = "Update Complete · DeskBot Restarting"
                            updateIslandState(IslandState.CONNECTED, "DeskBot Restarting…")
                        } else if (statusText.contains("Receiving", ignoreCase = true)) {
                            tvOtaStatusLabel.text = "Flashing firmware to DeskBot…"
                        } else if (statusText.contains("failed", ignoreCase = true) || statusText.contains("Cannot", ignoreCase = true)) {
                            Toast.makeText(this@MainActivity, statusText, Toast.LENGTH_SHORT).show()
                        }
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
                    runOnUiThread {
                        tvTelemetryMtu.text = "$mtu B"
                    }
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
        }

        // Connect directly via BLE GATT with TRANSPORT_LE for instant, fast BLE connection!
        activeGatt = @Suppress("DEPRECATION") dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Triggered on successful Bluetooth connection!
     * Executes the energized iOS NameDrop wave animation, sound/haptics,
     * blooms the Dynamic Island, and transitions UI elements with smooth spring physics.
     */
    private fun onDeskBotSuccessfullyConnected(deviceName: String) {
        isConnected = true

        // 1. Trigger the Energized iOS NameDrop Animation!
        val islandLocation = IntArray(2)
        layoutDynamicIsland.getLocationOnScreen(islandLocation)
        val dropOriginX = islandLocation[0] + (layoutDynamicIsland.width / 2f)
        val dropOriginY = islandLocation[1] + (layoutDynamicIsland.height / 2f)

        nameDropView.triggerNameDrop(dropOriginX, dropOriginY) {
            // Callback when NameDrop completes
        }

        // 2. Dynamic Island Bloom
        updateIslandState(IslandState.CONNECTED, "$deviceName Connected")

        // 3. Hero Card Smooth Morph Animation
        tvHeroTitle.text = deviceName
        tvHeroSubtitle.text = getString(R.string.connected_hint)
        ivHeroRobot.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ios_green))
        layoutOrbContainer.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3330D158"))

        // Animate Connect button morphing into Disconnect button
        animateButtonStateTransition(toConnected = true)

        // 4. Update Telemetry
        tvTelemetryStatus.text = getString(R.string.status_online)
        tvTelemetryStatus.setTextColor(ContextCompat.getColor(this, R.color.ios_green))
        tvTelemetryMtu.text = "$negotiatedMtu B"

        // 5. Hero Card Pulse Bounce
        findViewById<View>(R.id.cardHeroDevice).apply {
            scaleX = 0.96f
            scaleY = 0.96f
            animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(450)
                .setInterpolator(iosSpringOvershoot)
                .start()
        }

        Toast.makeText(this, "Connected to $deviceName!", Toast.LENGTH_SHORT).show()
    }

    private fun animateButtonStateTransition(toConnected: Boolean) {
        isAnimating = true
        val targetText = if (toConnected) getString(R.string.disconnect) else getString(R.string.connect)
        val targetIcon = if (toConnected) R.drawable.ic_ios_close else R.drawable.ic_ios_bluetooth
        val targetBg = if (toConnected) R.drawable.bg_ios_destructive_button else R.drawable.bg_ios_primary_button

        btnMainAction.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .alpha(0.3f)
            .setDuration(160)
            .setInterpolator(iosEaseInOut)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    btnMainAction.text = targetText
                    btnMainAction.setIconResource(targetIcon)
                    btnMainAction.setBackgroundResource(targetBg)
                    btnMainAction.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.ios_label_primary))

                    btnMainAction.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(1.0f)
                        .setDuration(240)
                        .setInterpolator(iosSpringOvershoot)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                isAnimating = false
                            }
                        })
                        .start()
                }
            })
            .start()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        val name = connectedDevice?.name ?: "DeskBot"
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (_: Exception) {}
        activeGatt = null
        commandCharacteristic = null
        firmwareCharacteristic = null
        connectedDevice = null
        isConnected = false
        isConnecting = false

        resetToDisconnectedUI()
        Toast.makeText(this, "Disconnected from $name", Toast.LENGTH_SHORT).show()
    }

    private fun resetToDisconnectedUI() {
        isConnected = false
        isConnecting = false

        updateIslandState(IslandState.IDLE)
        tvHeroTitle.text = "DeskBot ESP32"
        tvHeroSubtitle.text = getString(R.string.connection_ready_hint)
        ivHeroRobot.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ios_cyan))
        layoutOrbContainer.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1A64D2FF"))

        animateButtonStateTransition(toConnected = false)

        tvTelemetryStatus.text = getString(R.string.status_offline)
        tvTelemetryStatus.setTextColor(ContextCompat.getColor(this, R.color.ios_label_secondary))
        tvTelemetryMtu.text = "23 B"

        if (isUpdateCheckActive || isUpdateAvailable) {
            resetUpdateState()
        }
    }

    private fun handleBluetoothConnectionLost() {
        if (!isConnected && activeGatt == null && connectedDevice == null) return

        @SuppressLint("MissingPermission")
        val devName = connectedDevice?.name ?: "DeskBot"
        try {
            activeGatt?.close()
        } catch (_: Exception) {}
        activeGatt = null
        commandCharacteristic = null
        firmwareCharacteristic = null
        connectedDevice = null

        if (isConnected) {
            Toast.makeText(this, "Connection lost with $devName", Toast.LENGTH_SHORT).show()
            resetToDisconnectedUI()
        }
    }

    // --- GitHub Firmware Updates & BLE OTA ---

    private fun checkForUpdates() {
        isUpdateCheckActive = true
        btnCheckUpdates.text = "…"
        tvUpdateStatus.text = getString(R.string.checking_updates)

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
                    val currentVersionTag = tvCurrentVersion.text.toString().trim()
                    val hasHigherVersion = latestTagName.isNotEmpty() && isHigherVersion(latestTagName, currentVersionTag)

                    withContext(Dispatchers.Main) {
                        isUpdateCheckActive = false

                        if (hasHigherVersion) {
                            latestVersionTag = latestTagName
                            isUpdateAvailable = true
                            tvUpdateStatus.text = getString(R.string.new_update_available)
                            tvUpdateStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ios_orange))

                            btnCheckUpdates.text = "Update"
                            btnCheckUpdates.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ios_orange))

                            // Show release notes card with smooth spring slide-down
                            layoutReleaseNotesCard.visibility = View.VISIBLE
                            layoutReleaseNotesCard.alpha = 0f
                            layoutReleaseNotesCard.translationY = -20f
                            layoutReleaseNotesCard.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(350)
                                .setInterpolator(iosSpringOvershoot)
                                .start()

                            tvReleaseNotesTitle.text = "DeskBot $latestTagName Available"
                            tvReleaseNotesDate.text = "Released: $formattedDate"
                            tvReleaseNotesContent.text = if (releaseBody.isNotEmpty()) releaseBody else "Bug fixes and performance improvements for DeskBot."
                            btnInstallUpdateNow.text = getString(R.string.update_to_version, latestTagName)
                        } else {
                            tvUpdateStatus.text = getString(R.string.currently_up_to_date)
                            tvUpdateStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ios_green))
                            btnCheckUpdates.text = "✓"
                            btnCheckUpdates.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ios_green))
                            layoutReleaseNotesCard.visibility = View.GONE
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isUpdateCheckActive = false
                        tvUpdateStatus.text = "Check failed ($responseCode)"
                        btnCheckUpdates.text = "Retry"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isUpdateCheckActive = false
                    tvUpdateStatus.text = "Network error"
                    btnCheckUpdates.text = "Retry"
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

    private fun resetUpdateState() {
        layoutReleaseNotesCard.visibility = View.GONE
        layoutOtaProgress.visibility = View.GONE
        isUpdateCheckActive = false
        isUpdateAvailable = false
        btnCheckUpdates.text = "Check"
        btnCheckUpdates.setTextColor(ContextCompat.getColor(this, R.color.ios_blue))
        tvUpdateStatus.text = getString(R.string.check_for_updates)
        tvUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.ios_label_secondary))
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
        btnCheckUpdates.isEnabled = false
        btnInstallUpdateNow.isEnabled = false

        layoutOtaProgress.visibility = View.VISIBLE
        progressBarOta.progress = 0
        tvOtaPercentage.text = "0%"
        tvOtaStatusLabel.text = getString(R.string.updating_firmware)

        updateIslandState(IslandState.UPDATING, "Updating DeskBot 0%")

        lifecycleScope.launch(Dispatchers.IO) {
            var firmware: File? = null
            try {
                firmware = downloadFirmware()
                val downloadedFirmware = requireNotNull(firmware)

                // BLE GATT OTA
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
                            progressBarOta.progress = percent
                            tvOtaPercentage.text = "$percent%"
                            updateIslandState(IslandState.UPDATING, "Updating DeskBot $percent%")
                        }
                    }
                }
                check(writeCharacteristicBlocking(gatt, command, "OTA_END".toByteArray())) {
                    "DeskBot could not finalize the update"
                }

                withContext(Dispatchers.Main) {
                    tvCurrentVersion.text = targetVersion
                    tvUpdateStatus.text = getString(R.string.currently_up_to_date)
                    tvUpdateStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ios_green))
                    updateIslandState(IslandState.CONNECTED, "DeskBot Updated!")
                    Toast.makeText(this@MainActivity, "Update successful. DeskBot is restarting.", Toast.LENGTH_LONG).show()

                    btnCheckUpdates.isEnabled = true
                    btnInstallUpdateNow.isEnabled = true
                    resetUpdateState()
                }
            } catch (e: Exception) {
                activeGatt?.let { gatt ->
                    commandCharacteristic?.let { command ->
                        writeCharacteristicBlocking(gatt, command, "OTA_ABORT".toByteArray())
                    }
                }
                withContext(Dispatchers.Main) {
                    btnCheckUpdates.isEnabled = true
                    btnInstallUpdateNow.isEnabled = true
                    tvUpdateStatus.text = "Update failed: ${e.message}"
                    tvUpdateStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ios_red))
                    layoutOtaProgress.visibility = View.GONE
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
            .setSmallIcon(R.drawable.ic_ios_bluetooth)
            .setContentTitle("Bluetooth Connection Failed")
            .setContentText("Could not connect to $deviceName")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Failed to connect to $deviceName.\nReason: $errorDetails"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            notificationManager.notify(1001, builder.build())
        } catch (_: SecurityException) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothDisconnectReceiver)
            stopBluetoothScan()
            activeGatt?.close()
        } catch (_: Exception) {}
    }
}