package com.example.brookselevatoraccess

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private const val TAG = "BluetoothHelper"

class BluetoothHelper(private val context: Context, private val lifecycleScope: LifecycleCoroutineScope, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO, private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothSocket: BluetoothSocket? = null
    private val myUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var bluetoothReceiver: BroadcastReceiver? = null

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private var isReceiverRegistered = false

    companion object {
        private const val BLUETOOTH_CONNECT_PERMISSION_MESSAGE =
            "Bluetooth connect permission is required."
        private const val REQUEST_CODE_ENABLE_BLUETOOTH = 1002
        private const val REQUEST_CODE_PERMISSIONS = 1003
    }

    init {
        //Initialize bluetoothAdapter.
        if (bluetoothAdapter == null) {
            Log.e("BluetoothHelper", "Device does not support Bluetooth.")
        }
    }

    private fun createBluetoothReceiver() {
        bluetoothReceiver = object : BroadcastReceiver() {
            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> handleDeviceFound(context, intent)

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> discoveryFinished(context)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun handleDeviceFound(context: Context?, intent: Intent?) {
        val device = intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        device?.let { newDevice ->
            if (ContextCompat.checkSelfPermission(
                    context!!,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    _discoveredDevices.value += newDevice
                    Log.d(TAG, "Device found: ${newDevice.name ?: "Unknown"} - ${newDevice.address}")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException accessing device name: ${e.message}")
                    Toast.makeText(
                        context,
                        "Bluetooth connect permission is required to access device name.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Log.e(TAG, "Bluetooth Connect permission is not granted.")
                Toast.makeText(context, BLUETOOTH_CONNECT_PERMISSION_MESSAGE, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun discoveryFinished(context: Context?) {
        Log.d("BluetoothHelper", "Discovery finished.")
        unregisterReceiver()
        Toast.makeText(
            context,
            "Bluetooth discovery has finished.",
            Toast.LENGTH_SHORT
        ).show()
        Log.d("Bluetooth Helper", "Discovery finished.")
    }

    //Called to start searching for devices.
    private fun startBluetoothDiscovery() {

        if (bluetoothAdapter == null) {
            Toast.makeText(
                context,
                "Device is not capable of Bluetooth connection.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        //Ensure that Bluetooth permissions are granted by the user.
        if (!checkBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        // Ensure that location permission is granted for Bluetooth scanning.
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Toast.makeText(
                context,
                "Location permission is required for Bluetooth scanning.",
                Toast.LENGTH_SHORT
            ).show()
            ActivityCompat.requestPermissions(
                context as Activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                101
            )
            return
        }

        //Invoke cancelDiscovery() is discovery is already in process.
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }

        _discoveredDevices.value = emptyList() //Clear previous devices.

        createBluetoothReceiver()

        //Register the receiver if it is not already.
        registerReceiver()

        //Invoke startDiscovery() to start Bluetooth discovery.
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.startDiscovery() // Safe call
            Toast.makeText(context, "Bluetooth discovery started.", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "Bluetooth SCAN permission is not granted.")
            Toast.makeText(context, "Bluetooth scan permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    // Called to stop searching for devices.
    fun stopBluetoothDiscovery() {
        //Make sure that location permission is granted before stopping discovery.
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, "Location permission is required.", Toast.LENGTH_SHORT).show()
            ActivityCompat.requestPermissions(
                context as Activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_CODE_PERMISSIONS
            )
            return
        }

        //Stop Bluetooth discovery when permission is granted.
        bluetoothAdapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
        Toast.makeText(context, "Bluetooth discovery stopped.", Toast.LENGTH_SHORT).show()

        //Unregister receiver.
        unregisterReceiver()

    }

    //BluetoothDevice represents a remote device.
    fun connectToDevice(device: BluetoothDevice, callback: (Boolean, Any?) -> Unit) {
        if (bluetoothAdapter?.isDiscovering == true) {
            stopBluetoothDiscovery()
        }

        if (!checkPermissions()) {
            Toast.makeText(context, "Bluetooth permissions are required.", Toast.LENGTH_SHORT).show()
            requestBluetoothPermissions()
            return
        }

        lifecycleScope.launch(ioDispatcher) {
            try {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    withContext(mainDispatcher) {
                        _connectionState.value = false
                        Toast.makeText(context, BLUETOOTH_CONNECT_PERMISSION_MESSAGE, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                try {
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(myUUID)
                } catch (e: SecurityException) {
                    Log.e("BluetoothHelper", "SecurityException creating socket: ${e.message}")
                    withContext(mainDispatcher) {
                        _connectionState.value = false
                        Toast.makeText(
                            context,
                            BLUETOOTH_CONNECT_PERMISSION_MESSAGE,
                            Toast.LENGTH_SHORT
                        ).show()
                        callback(false, e.message)
                    }
                    return@launch
                }

                try {
                    bluetoothSocket?.connect()
                    withContext(mainDispatcher) {
                        _connectionState.value = true
                        Toast.makeText(context, "Connected to: ${device.name}", Toast.LENGTH_SHORT).show()
                    }

                    manageDataCommunications(bluetoothSocket!!)
                } catch (e: IOException) {
                    Log.e(TAG, "Could not connect to device: ${e.message}")
                    withContext(mainDispatcher) {
                        _connectionState.value = false
                        Toast.makeText(context, "Failed to connect to ${device.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed: ${e.message}")
                withContext(mainDispatcher) {
                    _connectionState.value = false
                    Toast.makeText(context, "Connection failed", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (!_connectionState.value) {
                    bluetoothSocket?.close()
                    bluetoothSocket = null
                }
            }
        }
    }


    private fun manageDataCommunications(bluetoothSocket: BluetoothSocket) {
        lifecycleScope.launch(ioDispatcher) {
            try {
                val inputStream = bluetoothSocket.inputStream
                val buffer = ByteArray(1024) //Adjustable as needed.
                var bytes: Int

                while (true) {
                    bytes = inputStream.read(buffer)
                    if (bytes == -1) break //End of the input stream.
                    val receivedData = String(buffer, 0, bytes)
                    Log.d(TAG, "Received: $receivedData")
                    withContext(mainDispatcher) {

                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error reading from socket: ${e.message}.")
                withContext(mainDispatcher) {
                    _connectionState.value = false
                }
            } finally {
                bluetoothSocket.close()
            }
        }
    }

    // Send command over Bluetooth on a background thread.
    fun sendCommand(command: String, onError: (String) -> Unit) {
        if (bluetoothSocket == null || bluetoothSocket?.isConnected == false) {
            onError("Bluetooth is not connected.")
            return
        }

        lifecycleScope.launch(ioDispatcher) {
            try {
                bluetoothSocket?.outputStream?.write(command.toByteArray())
                bluetoothSocket?.outputStream?.flush() //Ensure data is sent immediately.
            } catch (e: IOException) {
                // On error, notify the user on the main thread
                withContext(mainDispatcher) {
                    onError("Failed to send command: ${e.message}")
                }
            }
        }
    }

    // Disconnect the Bluetooth connection and close the socket
    fun disconnect(onDisconnected: () -> Unit, onError: (String) -> Unit) {
        lifecycleScope.launch {
            try {
                bluetoothSocket?.close()
                bluetoothSocket = null
                // Notify UI on the main thread
                withContext(mainDispatcher) {
                    _connectionState.value = false
                    onDisconnected()
                }
            } catch (e: IOException) {
                // On error, notify the user on the main thread
                withContext(mainDispatcher) {
                    onError("Failed to disconnect: ${e.message}")
                }
            }
        }
    }

    fun onDestroy() {
        stopBluetoothDiscovery()
        unregisterReceiver()
    }

    private fun isBluetoothEnabled() : Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    private fun enableBluetooth(launcher: ActivityResultLauncher<Intent>, callback: (Boolean) -> Unit) {
        val bluetoothAdapter: BluetoothAdapter? by lazy {
            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            bluetoothManager?.adapter
        }
        if (bluetoothAdapter?.isEnabled == false) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            launcher.launch(enableIntent)
        } else {
            callback(true)
        }
    }

    fun ensureBluetoothEnabledAndDiscover(launcher: ActivityResultLauncher<Intent>) {
        Log.d(TAG, "Checking if Bluetooth is enabled...")

        if (isBluetoothEnabled()) {
            Log.d(TAG, "Bluetooth is already enabled. Starting Discovery...")
            startBluetoothDiscovery()
        } else {
            Log.d(TAG, "Bluetooth is not enabled. Requesting to enable it...")
            enableBluetooth(launcher) { enabled ->
                Log.d(TAG, "Bluetooth enabled status: $enabled")
                if (enabled) {
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(2000)
                        Log.d(TAG, "Starting discovery after enabling Bluetooth...")
                        startBluetoothDiscovery()
                    }
                } else {
                    Toast.makeText(
                        context,
                        "Bluetooth must be enabled for discovery.",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.d(TAG, "Bluetooth enabling failed or was denied.")
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val locationPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        )
        val bluetoothPermission = ContextCompat.checkSelfPermission(
            context, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_CONNECT
            } else {
                Manifest.permission.BLUETOOTH
            }
        )
        return locationPermission == PackageManager.PERMISSION_GRANTED &&
                bluetoothPermission == PackageManager.PERMISSION_GRANTED
    }

    private fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH)
        } == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothPermissions() {
        if (context is Activity) { //Only request if context is an activity.
            ActivityCompat.requestPermissions(
                context,
                arrayOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Manifest.permission.BLUETOOTH_CONNECT
                    } else {
                        Manifest.permission.BLUETOOTH
                    },
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                101
            )
        } else {
            Log.e("BluetoothHelper", "Context is not an Activity, cannot request permissions.")
        }
    }

    private fun registerReceiver() {
        if (!isReceiverRegistered && bluetoothReceiver != null) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(bluetoothReceiver, filter)
            isReceiverRegistered = true
        }
    }

    private fun unregisterReceiver() {
        bluetoothReceiver?.let {
            try {
                if (isReceiverRegistered) {
                    context.unregisterReceiver(it)
                    isReceiverRegistered = false
                    return@let
                }
            } catch (e: IllegalArgumentException) {
                Log.e("BluetoothHelper", "Error unregistering receiver: ${e.message}.")
            }
        }
        bluetoothReceiver = null
    }


}






