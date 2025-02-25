package com.example.brookselevatoraccess.com.example.brookselevatoraccess.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.brookselevatoraccess.com.example.brookselevatoraccess.BluetoothDeviceDomain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@RequiresApi(Build.VERSION_CODES.M)
class AndroidBluetoothController(private val context: Context) {

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = true
    }

    fun isConnected(): Boolean {
        return _connectionState.value
    }

    fun disconnect() {
        _connectionState.value = false
    }

    private val bluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }
    private val bluetoothAdapter by lazy {
        bluetoothManager?.adapter
    }

    private val _scannedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDeviceDomain>>
        get() = _scannedDevices.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDeviceDomain>>
        get() = _pairedDevices.asStateFlow()

    private val scannedDeviceSet = mutableSetOf<BluetoothDeviceDomain>()
    private var isReceiverRegistered = false

    private val foundDeviceReceiver = FoundDeviceReceiver { device: BluetoothDevice ->
        val newDevice = device.toBluetoothDeviceDomain()
        if (scannedDeviceSet.add(newDevice)) {
            _scannedDevices.update { scannedDeviceSet.toList() }
        }
    }

    init {
        updatePairedDevices()
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            return
        }

        if (!isReceiverRegistered) {
            context.registerReceiver(
                foundDeviceReceiver,
                IntentFilter(BluetoothDevice.ACTION_FOUND)
            )
            isReceiverRegistered = true
        }

        updatePairedDevices()
        bluetoothAdapter?.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            return
        }

        bluetoothAdapter?.cancelDiscovery()
    }

    fun release() {
        if (isReceiverRegistered) {
            context.unregisterReceiver(foundDeviceReceiver)
            isReceiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun updatePairedDevices() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            return
        }

        val devices =
            bluetoothAdapter?.bondedDevices?.map { it.toBluetoothDeviceDomain() } ?: emptyList()
        _pairedDevices.update { devices }
    }


    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }


    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toBluetoothDeviceDomain(): BluetoothDeviceDomain {
        return BluetoothDeviceDomain(
            name = this.name ?: "Unknown Device",
            address = this.address
        )
    }

    @SuppressLint("MissingPermission")
    fun isCurrentlyConnected(): Boolean {
        var isConnected = false

        //Check if the Bluetooth adapter is readily available.
        bluetoothAdapter?.let { adapter ->
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    //Check connected devices for the profile.
                    val connectedDevices = proxy?.connectedDevices
                    if (connectedDevices != null) {
                        isConnected = connectedDevices.isNotEmpty()
                    }
                    adapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {
                    //Implement if necessary.
                }
            }, BluetoothProfile.GATT) //Use appropriate profile.
        }
        return isConnected
    }

    @SuppressLint("MissingPermission")
    private fun updateConnectionState() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            _connectionState.value = false
            return
        }

        bluetoothAdapter?.let { adapter ->
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    val connectedDevices = proxy?.connectedDevices
                    _connectionState.value = connectedDevices?.isNotEmpty() == true
                    adapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {
                    _connectionState.value = false
                }
            }, BluetoothProfile.GATT)
        }
    }
}


