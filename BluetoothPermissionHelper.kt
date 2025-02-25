package com.example.brookselevatoraccess.com.example.brookselevatoraccess

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class BluetoothPermissionHelper {

    //Request Bluetooth permissions from the user.
    @SuppressLint("LongLogTag")
    fun requestBluetoothPermission(context: Context) {
        val permission = mutableListOf<String>()

        //Add permissions based on Android version.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permission.add(Manifest.permission.BLUETOOTH_CONNECT)
            permission.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permission.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        //Check which permissions are not granted.
        val permissionToRequest = permission.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        //Request permissions that are not granted.
        if (permissionToRequest.isNotEmpty() && context is Activity) {
            ActivityCompat.requestPermissions(
                context,
                permissionToRequest.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            //Handle if the context is not an Activity.
            Log.e("BluetoothPermissionHelper", "Context must be an Activity.")
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 101
    }

    fun hasBluetoothPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

}