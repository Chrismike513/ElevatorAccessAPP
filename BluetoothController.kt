package com.example.brookselevatoraccess.com.example.brookselevatoraccess

import com.example.brookselevatoraccess.com.example.brookselevatoraccess.data.BluetoothDevice
import kotlinx.coroutines.flow.StateFlow

interface BluetoothController {
    val scannedDevices: StateFlow<List<BluetoothDevice>> //Scanned devices.
    val pairedDevices: StateFlow<List<BluetoothDevice>> //Paired devices.
    val connectionState: StateFlow<Boolean> //Indicates if Bluetooth is connected.

    fun startDiscovery() //Start scanning process for Bluetooth devices.
    fun stopDiscovery() //Stop scanning process for Bluetooth devices.

    fun isCurrentlyConnected(): Boolean //Checks if a device is connected in real time.

    fun release()
}