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
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
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
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

data class RealBluetoothDeviceItem(
    val name: String,
    val address: String,
    val isBonded: Boolean,
    val device: BluetoothDevice
)

class MainActivity : AppCompatActivity() {

    private var isConnected = false
    private var isAnimating = false

    // Bluetooth properties
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var activeSocket: BluetoothSocket? = null
    private var connectedDevice: BluetoothDevice? = null
    private val realDevicesList = mutableListOf<RealBluetoothDeviceItem>()
    private var devicesAdapter: ArrayAdapter<RealBluetoothDeviceItem>? = null
    private var scanProgressBar: ProgressBar? = null
    private var emptyTextView: TextView? = null

    // Pending anchor views for click action after permission check
    private var pendingAnchorButton: MaterialButton? = null
    private var pendingTargetRefButton: MaterialButton? = null

    // Update state properties
    private var isUpdateCheckActive = false
    private var isUpdateAvailable = false
    private var latestVersionTag: String? = null

    // Default dimensions & position for the "Connect" (center) state
    private var initialX = 0f
    private var initialY = 0f
    private var initialWidth = 0
    private var initialHeight = 0
    private var initialRadius = 0
    private var isInitialCaptured = false

    // Standard SPP UUID for Serial Port Profile communication
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBluetoothAndShowDevices()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required to connect to real devices.", Toast.LENGTH_LONG).show()
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

    // BroadcastReceiver for discovering Bluetooth devices and detecting connection drops
    private val bluetoothDiscoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    device?.let { dev ->
                        val name = dev.name ?: ""
                        // Only include devices whose name starts with "DeskBot"
                        if (name.startsWith("DeskBot", ignoreCase = true)) {
                            if (realDevicesList.none { it.address == dev.address }) {
                                val newItem = RealBluetoothDeviceItem(name, dev.address, isBonded = false, device = dev)
                                realDevicesList.add(newItem)
                                devicesAdapter?.notifyDataSetChanged()
                                emptyTextView?.visibility = View.GONE
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanProgressBar?.visibility = View.GONE
                }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
        }
        registerReceiver(bluetoothDiscoveryReceiver, filter)

        val connectButton = findViewById<MaterialButton>(R.id.button)
        val targetRefButton = findViewById<MaterialButton>(R.id.button3)
        val updateButton = findViewById<MaterialButton>(R.id.button2)
        val versionView = findViewById<TextView>(R.id.versionView)
        val updateCardView = findViewById<View>(R.id.updateCardView)

        targetRefButton.visibility = View.INVISIBLE

        connectButton.post {
            captureInitialBounds(connectButton)
        }

        connectButton.setOnClickListener {
            if (isAnimating) return@setOnClickListener
            if (!isInitialCaptured) {
                captureInitialBounds(connectButton)
            }

            pendingAnchorButton = connectButton
            pendingTargetRefButton = targetRefButton

            if (!isConnected) {
                if (!hasBluetoothPermissions()) {
                    requestPermissionLauncher.launch(getRequiredBluetoothPermissions())
                } else {
                    checkBluetoothAndShowDevices()
                }
            } else {
                // Disconnect real device and rewind animation
                disconnectRealDevice()
                animateLiquidTransition(connectButton, targetRefButton)
            }
        }

        updateButton.setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "Please connect to a DeskBot device via Bluetooth first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isUpdateAvailable) {
                // User clicked "Update to <version>" -> Send "Update" command over Bluetooth
                val targetVersion = latestVersionTag ?: "new version"
                sendBluetoothCommand("Update\n")

                Toast.makeText(this, "Sending Update signal to DeskBot over Bluetooth...", Toast.LENGTH_SHORT).show()
                updateButton.text = getString(R.string.updating_firmware)

                // Update version text view and reset state
                versionView.text = targetVersion
                updateButton.postDelayed({
                    resetUpdateStateAndSlideDown(updateButton, connectButton, updateCardView)
                }, 2000)
            } else if (!isUpdateCheckActive) {
                // Slide update button to top, make disconnect button invisible, and check for updates
                slideUpAndCheckForUpdates(updateButton, connectButton, versionView, updateCardView)
            } else {
                // Toggle back if active
                resetUpdateStateAndSlideDown(updateButton, connectButton, updateCardView)
            }
        }
    }

    private fun handleBluetoothConnectionLost() {
        if (!isConnected && activeSocket == null && connectedDevice == null) return

        @SuppressLint("MissingPermission")
        val devName = connectedDevice?.name ?: "Bluetooth device"
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null
        connectedDevice = null

        if (isConnected) {
            Toast.makeText(this, "Bluetooth connection lost ($devName)", Toast.LENGTH_SHORT).show()

            val updateButton = findViewById<MaterialButton>(R.id.button2)
            val connectButton = findViewById<MaterialButton>(R.id.button)
            val targetRefButton = findViewById<MaterialButton>(R.id.button3)
            val updateCardView = findViewById<View>(R.id.updateCardView)

            if (isUpdateCheckActive || isUpdateAvailable) {
                resetUpdateStateAndSlideDown(updateButton, connectButton, updateCardView)
            }

            // Rewind liquid bounce animation back to center "Connect" state
            animateLiquidTransition(connectButton, targetRefButton)
        }
    }

    private fun slideUpAndCheckForUpdates(
        updateButton: MaterialButton,
        connectButton: MaterialButton,
        versionView: TextView,
        updateCardView: View
    ) {
        isUpdateCheckActive = true

        // Make the Disconnect button invisible with fade animation
        connectButton.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { connectButton.visibility = View.INVISIBLE }
            .start()

        // Slide update button up to top of screen
        val targetYOffset = -(updateButton.top - (23 * resources.displayMetrics.density))
        updateButton.animate()
            .translationY(targetYOffset)
            .setDuration(600)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        updateButton.text = "Checking for updates..."

        // Fetch latest release from GitHub API
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
                    val currentVersionTag = versionView.text.toString().trim()
                    val hasHigherVersion = latestTagName.isNotEmpty() && isHigherVersion(latestTagName, currentVersionTag)

                    withContext(Dispatchers.Main) {
                        // Show Release Notes / Info Card with fade-in
                        updateCardView.visibility = View.VISIBLE
                        updateCardView.alpha = 0f
                        updateCardView.animate().alpha(1f).setDuration(400).start()

                        val txtUpdateHeader = findViewById<TextView>(R.id.txtUpdateHeader)
                        val txtCurrentVersion = findViewById<TextView>(R.id.txtCurrentVersion)
                        val txtLatestVersion = findViewById<TextView>(R.id.txtLatestVersion)
                        val txtReleaseDate = findViewById<TextView>(R.id.txtReleaseDate)
                        val txtReleaseNotes = findViewById<TextView>(R.id.txtReleaseNotes)
                        val btnCloseCard = findViewById<MaterialButton>(R.id.btnCloseUpdateCard)

                        txtCurrentVersion.text = "Installed: $currentVersionTag"
                        txtLatestVersion.text = "Latest: $latestTagName"
                        txtReleaseDate.text = "Date: $formattedDate"
                        txtReleaseNotes.text = if (releaseBody.isNotEmpty()) releaseBody else "No release notes specified for this version."

                        if (hasHigherVersion) {
                            txtUpdateHeader.text = getString(R.string.new_update_available)
                            txtUpdateHeader.setTextColor("#FF1744".toColorInt())

                            latestVersionTag = latestTagName
                            isUpdateAvailable = true
                            updateButton.text = getString(R.string.update_to_version, latestTagName)
                            updateButton.strokeColor = ColorStateList.valueOf("#FF1744".toColorInt())
                            updateButton.backgroundTintList = ColorStateList.valueOf("#1A00E5FF".toColorInt())
                            btnCloseCard.visibility = View.GONE
                        } else {
                            txtUpdateHeader.text = getString(R.string.currently_up_to_date)
                            txtUpdateHeader.setTextColor("#00E5FF".toColorInt())

                            updateButton.text = "Already up to date"
                            btnCloseCard.visibility = View.VISIBLE
                            btnCloseCard.setOnClickListener {
                                resetUpdateStateAndSlideDown(updateButton, connectButton, updateCardView)
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        updateButton.text = "Update check failed ($responseCode)"
                        updateButton.postDelayed({
                            resetUpdateStateAndSlideDown(updateButton, connectButton, updateCardView)
                        }, 2500)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    updateButton.text = "Update check failed"
                    Toast.makeText(this@MainActivity, "Network error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    updateButton.postDelayed({
                        resetUpdateStateAndSlideDown(updateButton, connectButton, updateCardView)
                    }, 2500)
                }
            }
        }
    }

    private fun parsePublishedDate(publishedAt: String): String {
        if (publishedAt.isEmpty()) return "N/A"
        return try {
            publishedAt.split("T").firstOrNull() ?: publishedAt
        } catch (_: Exception) {
            publishedAt
        }
    }

    private fun resetUpdateStateAndSlideDown(
        updateButton: MaterialButton,
        connectButton: MaterialButton,
        updateCardView: View
    ) {
        // Fade out Release Notes Card
        updateCardView.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { updateCardView.visibility = View.GONE }
            .start()

        // Slide update button back down to bottom position
        updateButton.animate()
            .translationY(0f)
            .setDuration(600)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                updateButton.text = getString(R.string.check_for_updates)
                updateButton.strokeColor = ColorStateList.valueOf("#00E5FF".toColorInt())
                updateButton.backgroundTintList = ColorStateList.valueOf("#121A24".toColorInt())

                // Restore top-right Disconnect button if still connected
                if (isConnected) {
                    connectButton.visibility = View.VISIBLE
                    connectButton.animate().alpha(1f).setDuration(300).start()
                }
            }
            .start()

        isUpdateCheckActive = false
        isUpdateAvailable = false
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

    private fun sendBluetoothCommand(command: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val outputStream = activeSocket?.outputStream
                if (outputStream != null) {
                    outputStream.write(command.toByteArray())
                    outputStream.flush()
                } else {
                    withContext(Dispatchers.Main) {
                        handleBluetoothConnectionLost()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    handleBluetoothConnectionLost()
                }
            }
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

        val anchor = pendingAnchorButton ?: return
        val targetRef = pendingTargetRefButton ?: return
        showRealBluetoothDevicesDropdown(anchor, targetRef)
    }

    @SuppressLint("MissingPermission")
    private fun showRealBluetoothDevicesDropdown(anchorButton: MaterialButton, targetRef: MaterialButton) {
        val rootLayout = findViewById<ViewGroup>(R.id.main)
        val popupView = layoutInflater.inflate(R.layout.popup_bluetooth_devices, rootLayout, false)
        val listView = popupView.findViewById<ListView>(R.id.deviceListView)
        val btnScan = popupView.findViewById<MaterialButton>(R.id.btnScanDevices)
        scanProgressBar = popupView.findViewById(R.id.scanProgressBar)
        emptyTextView = popupView.findViewById(R.id.emptyDevicesText)

        realDevicesList.clear()

        // Populate paired/bonded devices starting with "DeskBot"
        val bonded = bluetoothAdapter?.bondedDevices
        bonded?.forEach { dev ->
            val name = dev.name ?: ""
            if (name.startsWith("DeskBot", ignoreCase = true)) {
                realDevicesList.add(RealBluetoothDeviceItem(name, dev.address, isBonded = true, device = dev))
            }
        }

        val popupWidth = (300 * resources.displayMetrics.density).toInt()
        val popupWindow = PopupWindow(
            popupView,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 16f
            isOutsideTouchable = true
            isFocusable = true
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
                view.findViewById<TextView>(R.id.deviceStatus).text = if (item?.isBonded == true) "PAIRED" else "SIGNAL"
                return view
            }
        }

        listView.adapter = devicesAdapter

        if (realDevicesList.isEmpty()) {
            emptyTextView?.visibility = View.VISIBLE
        } else {
            emptyTextView?.visibility = View.GONE
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = realDevicesList[position]
            popupWindow.dismiss()
            stopBluetoothScan()

            // Connect to real selected device
            connectToRealDeviceAndAnimate(selected, anchorButton, targetRef)
        }

        btnScan.setOnClickListener {
            startBluetoothScan()
        }

        // Position dropdown centered directly underneath the connect button
        val xOffset = (anchorButton.width - popupWidth) / 2
        val yOffset = (12 * resources.displayMetrics.density).toInt()
        popupWindow.showAsDropDown(anchorButton, xOffset, yOffset)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        if (!hasBluetoothPermissions()) return
        val adapter = bluetoothAdapter ?: return

        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }

        scanProgressBar?.visibility = View.VISIBLE
        adapter.startDiscovery()
        Toast.makeText(this, "Scanning for nearby DeskBot devices...", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        if (hasBluetoothPermissions() && bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToRealDeviceAndAnimate(
        item: RealBluetoothDeviceItem,
        button: MaterialButton,
        targetRef: MaterialButton
    ) {
        val dev = item.device
        Toast.makeText(this, "Connecting to ${item.name}...", Toast.LENGTH_SHORT).show()

        val originalText = button.text.toString()
        button.text = getString(R.string.connecting)

        // Attempt real Bluetooth RFCOMM socket connection asynchronously
        lifecycleScope.launch(Dispatchers.IO) {
            stopBluetoothScan()
            var connectionSuccessful = false
            var errorMessage = ""

            try {
                activeSocket?.close()
            } catch (_: Exception) {}

            try {
                val socket = dev.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                activeSocket = socket
                connectedDevice = dev
                connectionSuccessful = true
            } catch (e: Exception) {
                errorMessage = e.localizedMessage ?: "Device unreachable or refusing connection"
                // Reflection fallback for certain Android hardware
                try {
                    val m = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    val fallbackSocket = m.invoke(dev, 1) as BluetoothSocket
                    fallbackSocket.connect()
                    activeSocket = fallbackSocket
                    connectedDevice = dev
                    connectionSuccessful = true
                } catch (e2: Exception) {
                    errorMessage = e2.localizedMessage ?: errorMessage
                    connectionSuccessful = false
                }
            }

            withContext(Dispatchers.Main) {
                button.text = originalText

                if (connectionSuccessful) {
                    Toast.makeText(this@MainActivity, "Connected to ${item.name}!", Toast.LENGTH_SHORT).show()
                    // Connection succeeded: Trigger liquid bouncing button animation
                    animateLiquidTransition(button, targetRef)
                } else {
                    // Connection failed: Keep button in NOT CONNECTED state and display error notification
                    val fullError = "Failed to connect to ${item.name}"
                    Toast.makeText(this@MainActivity, "$fullError: $errorMessage", Toast.LENGTH_LONG).show()
                    showConnectionErrorNotification(item.name, errorMessage)
                }
            }
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
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Bluetooth Connection Failed")
            .setContentText("Could not connect to $deviceName")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Failed to connect to $deviceName.\nReason: $errorDetails"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            notificationManager.notify(1001, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectRealDevice() {
        val name = connectedDevice?.name ?: "Bluetooth device"
        try {
            activeSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        activeSocket = null
        connectedDevice = null
        Toast.makeText(this, "Disconnected from $name", Toast.LENGTH_SHORT).show()
    }

    private fun captureInitialBounds(button: MaterialButton) {
        if (!isInitialCaptured && button.width > 0 && button.height > 0) {
            initialX = button.x - button.translationX
            initialY = button.y - button.translationY
            initialWidth = button.width
            initialHeight = button.height
            initialRadius = button.height / 2
            isInitialCaptured = true
        }
    }

    private fun animateLiquidTransition(button: MaterialButton, targetRef: MaterialButton) {
        isAnimating = true
        val goingToConnect = !isConnected
        isConnected = goingToConnect

        val startX = button.x
        val startY = button.y
        val startWidth = button.width
        val startHeight = button.height
        val startRadius = button.cornerRadius

        val targetX = if (goingToConnect) targetRef.x else initialX
        val targetY = if (goingToConnect) targetRef.y else initialY
        val targetWidth = if (goingToConnect) targetRef.width else initialWidth
        val targetHeight = if (goingToConnect) targetRef.height else initialHeight
        val targetRadius = if (goingToConnect) (20 * resources.displayMetrics.density).toInt() else initialRadius

        val startTextSizeSp = if (goingToConnect) 20f else 11f
        val targetTextSizeSp = if (goingToConnect) 11f else 20f

        val startBgColor = if (goingToConnect) "#00E5FF".toColorInt() else "#FF1744".toColorInt()
        val targetBgColor = if (goingToConnect) "#FF1744".toColorInt() else "#00E5FF".toColorInt()

        val startTextColor = if (goingToConnect) "#000000".toColorInt() else "#FFFFFF".toColorInt()
        val targetTextColor = if (goingToConnect) "#FFFFFF".toColorInt() else "#000000".toColorInt()

        val startStrokeColor = if (goingToConnect) "#80D8FF".toColorInt() else "#FF80AB".toColorInt()
        val targetStrokeColor = if (goingToConnect) "#FF80AB".toColorInt() else "#80D8FF".toColorInt()

        val targetText = if (goingToConnect) getString(R.string.disconnect) else getString(R.string.connect)

        val colorEvaluator = ArgbEvaluator()
        var textSwapped = false

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800L
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { anim ->
                val p = anim.animatedValue as Float

                val posProgress = calculateLiquidOvershoot(p)
                val desiredX = startX + (targetX - startX) * posProgress
                val desiredY = startY + (targetY - startY) * posProgress

                button.translationX = desiredX - button.left.toFloat()
                button.translationY = desiredY - button.top.toFloat()

                val (scaleX, scaleY) = calculateLiquidSquishAndWobble(p)
                button.scaleX = scaleX
                button.scaleY = scaleY

                val currentWidth = (startWidth + (targetWidth - startWidth) * p).toInt()
                val currentHeight = (startHeight + (targetHeight - startHeight) * p).toInt()
                val currentRadius = (startRadius + (targetRadius - startRadius) * p).toInt()

                val params = button.layoutParams
                if (params.width != currentWidth || params.height != currentHeight) {
                    params.width = currentWidth
                    params.height = currentHeight
                    button.layoutParams = params
                }
                button.cornerRadius = currentRadius

                val currentTextSizeSp = startTextSizeSp + (targetTextSizeSp - startTextSizeSp) * p
                button.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSizeSp)

                val currentBgColor = colorEvaluator.evaluate(p, startBgColor, targetBgColor) as Int
                val currentTextColor = colorEvaluator.evaluate(p, startTextColor, targetTextColor) as Int
                val currentStrokeColor = colorEvaluator.evaluate(p, startStrokeColor, targetStrokeColor) as Int

                button.backgroundTintList = ColorStateList.valueOf(currentBgColor)
                button.setTextColor(currentTextColor)
                button.strokeColor = ColorStateList.valueOf(currentStrokeColor)

                if (p in 0.35f..0.65f) {
                    val textAlpha = if (p <= 0.5f) {
                        1f - (p - 0.35f) / 0.15f
                    } else {
                        (p - 0.5f) / 0.15f
                    }
                    button.alpha = textAlpha.coerceIn(0.15f, 1f)

                    if (p >= 0.5f && !textSwapped) {
                        button.text = targetText
                        textSwapped = true
                    }
                } else if (p > 0.65f) {
                    button.alpha = 1f
                }
            }
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                button.alpha = 1f
                button.scaleX = 1f
                button.scaleY = 1f
                button.text = targetText
                button.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetTextSizeSp)
                button.setTextColor(targetTextColor)
                button.strokeColor = ColorStateList.valueOf(targetStrokeColor)
                isAnimating = false
            }
        })

        animator.start()
    }

    private fun calculateLiquidOvershoot(p: Float): Float {
        val base = 1.0f - exp(-5f * p) * cos(2.2f * Math.PI.toFloat() * p)
        val organicBounce = 0.15f * p * (1f - p) * (1f - p)
        return base + organicBounce
    }

    private fun calculateLiquidSquishAndWobble(p: Float): Pair<Float, Float> {
        val flightStretch = sin(p * Math.PI.toFloat())
        val stretchX = 0.14f * flightStretch * (1f - p)
        val stretchY = -0.10f * flightStretch * (1f - p)

        val wobbleFreq = 4.0f * Math.PI.toFloat()
        val decay = exp(-4.5f * p)
        val wobble = 0.22f * decay * sin(p * wobbleFreq) * p

        val scaleX = 1.0f + stretchX + wobble
        val scaleY = 1.0f - stretchY - wobble

        return Pair(scaleX, scaleY)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothDiscoveryReceiver)
            activeSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}