package com.example.brookselevatoraccess

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import com.example.brookselevatoraccess.com.example.brookselevatoraccess.BluetoothPermissionHelper
import com.example.brookselevatoraccess.ui.theme.BrooksElevatorAccessTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean


//Definition of a standard bluetooth UUID.
private val MY_UUID: UUID = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")


@Composable
fun ConfirmationDialog(floorNumber: Int, onConfirm: () -> Unit, onDismiss: () -> Unit, showDialog: MutableState<Boolean>) {
    if(showDialog.value) {
        AlertDialog(
            onDismissRequest = {
                showDialog.value = false
                onDismiss()
            },
            title = { Text("Confirm Floor Selection.") },
            text = { Text("Do you want to go to floor $floorNumber?") },
            confirmButton = {
                Button(onClick = {
                    showDialog.value = false
                    onConfirm()
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                Button(onClick = {
                    showDialog.value = false
                    onDismiss()
                }) {
                    Text("No")
                }
            }
        )
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothHelper: BluetoothHelper

    private lateinit var voiceRecognizerHelper: VoiceRecognizerHelper

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private lateinit var voiceRecognitionLauncher: ActivityResultLauncher<Intent>

    private lateinit var enableBluetoothLauncher: ActivityResultLauncher<Intent>


    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionHelper = BluetoothPermissionHelper()
        permissionHelper.requestBluetoothPermission(this)

        voiceRecognizerHelper = VoiceRecognizerHelper(application)

        registerEnableBluetoothLauncher()

        registerRequestPermissionLauncher()

        setupVoiceRecognition()

        initializeBluetoothAdapter()

        bluetoothHelper = BluetoothHelper(this, lifecycleScope)

        //Set the content view using Jetpack Compose.
        setContent {
            SetupUi()
        }
    }

    private fun registerEnableBluetoothLauncher() {
        enableBluetoothLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        if (bluetoothAdapter.isEnabled) {
                            Toast.makeText(this, "Bluetooth Enabled", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Bluetooth has been disabled.", Toast.LENGTH_SHORT)
                                .show()
                        }
                    } else {
                        Toast.makeText(
                            this,
                            "Bluetooth permission not granted.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(this, "Bluetooth enabling canceled.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun registerRequestPermissionLauncher() {
        requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->

                val microphoneGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
                val bluetoothGranted = permissions[if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Manifest.permission.BLUETOOTH_CONNECT
                } else {
                    Manifest.permission.BLUETOOTH
                }] ?: false

                if (microphoneGranted) {
                    startVoiceRec()
                } else {
                    Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_SHORT)
                        .show()
                }

                if (bluetoothGranted) {
                    setupBluetooth() //Proceed with Bluetooth initialization.
                } else {
                    Toast.makeText(
                        this,
                        "Bluetooth connect permission is required.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        //Request permissions at launch.
        requestPermissionLauncher.launch(
            arrayOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_CONNECT
            } else {
                Manifest.permission.BLUETOOTH
            }, Manifest.permission.RECORD_AUDIO)
        )
    }


    private fun setupBluetooth() {

        //Get BluetoothAdapter.
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        //Check for permission.
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            //Request the permission.
            requestPermissionLauncher.launch(arrayOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_CONNECT
            } else {
                Manifest.permission.BLUETOOTH
            }))
            return //Stops execution until permission is granted.
        }

        //Ensure bluetoothAdapter is not null.
        bluetoothAdapter.let { adapter ->
            if (!adapter.isEnabled) {
                val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(enableBluetoothIntent)
            }
        }

    }


    private fun setupVoiceRecognition() {
        // Initialize the voice recognition launcher
        voiceRecognitionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> //Registers a request to start an activity for the result, designated by the given contract.
                if (result.resultCode == RESULT_OK && result.data != null) { //Extracts the resultCode.
                    val spokenText =
                        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
                    if (!spokenText.isNullOrEmpty()) {
                        // Handle the recognized text here
                        processVoiceCommand(spokenText)
                    } else {
                        Toast.makeText(
                            this,
                            "No speech detected. Please try again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(this, "Voice recognition failed.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun initializeBluetoothAdapter() {
        // Get BluetoothManager system service
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available.", Toast.LENGTH_SHORT).show()
            return
        }

        //If Bluetooth is off, prompt the user to enable it.
        if (!bluetoothAdapter.isEnabled) {
            val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBluetoothIntent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Composable
    private fun SetupUi() {
        //Floor number input.
        val floorNumber by remember { mutableIntStateOf(0) }
        val showDialog = remember { mutableStateOf(false) }

        // Voice recognition
        var canRecord by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val activity = context as? Activity

        val recordAudioLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                canRecord = isGranted
                if (isGranted) {
                    //Only start listening if permission is granted.
                voiceRecognizerHelper.startListening()
                } else {
                    //Shows toast is permission is denied.
                    showToast(this, "Microphone permission is required.")
                }
            }
        )

        //Request mic permission once the composable is launched.
        LaunchedEffect(Unit) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        //State of the voice recognizer.
        val state by voiceRecognizerHelper.state.collectAsState()

        BrooksElevatorAccessTheme {

            val scrollState = rememberScrollState()

            val selectedMode = rememberSaveable { mutableStateOf("Elevator Access") }

            Scaffold { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp)
                        .verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // DropDown menu for mode selection
                    DropDown(onModeSelected = { mode ->
                        selectedMode.value = mode
                        Log.d("DropDown", "Mode selected: ${selectedMode.value}")
                    }) // Displays mode selection drop-down bar

                    // Condition-based content rendering
                    when (selectedMode.value) {
                        "Null" -> BlankMode() // Selecting Null goes to the filler mode.
                        else -> {
                            BluetoothUi() //Invoking the Bluetooth UI function.
                            SetupMicButton(state, canRecord, recordAudioLauncher, activity)

                            ConfirmationDialog(
                                floorNumber = floorNumber,
                                onConfirm = {
                                    //Handle confirm action.
                                    Log.d("Confirmation", "Floor $floorNumber confirmed.")
                                    bluetoothHelper.sendCommand("FLOOR_$floorNumber") { error ->
                                        Toast.makeText(context, "Error: $error", Toast.LENGTH_SHORT).show()
                                    } //Send command to the Raspberry Pi.
                                },
                                onDismiss = {
                                    Log.d("Confirmation", "Floor $floorNumber incorrect.")
                                },
                                showDialog = showDialog
                            )
                            // Elevator floor number buttons
                            ElevatorButtons(bluetoothHelper)
                        }
                    }
                }
            }
        }
    }


    //Creates the composable for the microphone button algorithm.
    @Composable
    private fun SetupMicButton(
        state: VoiceRecognizerHelper.VoiceToTextParseState,
        canRecord: Boolean,
        recordAudioLauncher: ActivityResultLauncher<String>,
        activity: Activity?
    ) {
        // Column containing the microphone button and elevator buttons

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            //Mic button
            LargeFloatingActionButton(
                onClick = {
                    if (!canRecord) {
                        recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        activity?.let {
                            if (!state.isSpeaking) {
                                voiceRecognizerHelper.startListening(it.toString())
                            } else {
                                voiceRecognizerHelper.stopListening()
                            }
                        }
                    }
                },
                modifier = Modifier
                    .size(125.dp)
                    .clip(CircleShape),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                MicButton(state = state)
            }

            Spacer(modifier = Modifier.height(20.dp))


            AnimatedContent(
                targetState = state.isSpeaking,
                label = "Microphone Button"
            ) { isSpeaking ->
                if (isSpeaking) {
                    Text(text = "Speaking...")
                } else {
                    Text(text = state.spokenText.ifEmpty { "Click the mic to speak." })
                }
            }
        }
    }

    //Creates the composable for the microphone button.
    @Composable
    private fun MicButton(state: VoiceRecognizerHelper.VoiceToTextParseState) {
        AnimatedContent(
            targetState = state.isSpeaking,
            label = "Microphone Button"
        ) { isSpeaking ->
            if (isSpeaking) {
                Icon(
                    painter = painterResource(id = R.drawable.baseline_stop_circle_24),
                    contentDescription = null,
                    modifier = Modifier.size(50.dp)
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.baseline_mic_24),
                    contentDescription = null,
                    modifier = Modifier.size(50.dp)
                )
            }
        }
    }


    //Function to take in the command as a String and pass it as data.
    private fun processVoiceCommand(command: String) {

        //Regular expression to extract floor numbers from voice command.
        val floorRegex = Regex("""(?:floor|F|level|)\s*(\d+)""", RegexOption.IGNORE_CASE)
        val result = floorRegex.find(command)

        if (result != null) {
            val floorNumber =
                result.groups[1]?.value?.toIntOrNull() //Extraction and conversion of the floor number.

            if (floorNumber in 1..100) {
                val piMessage = "FLOOR_$floorNumber" //Formats data for the pi.
                //Sends floor data.
                bluetoothHelper.sendCommand(piMessage) { errorMessage ->
                    Toast.makeText(this, "Failed to send: $errorMessage", Toast.LENGTH_SHORT)
                        .show()
                }
                Toast.makeText(this, "Sent: Floor $floorNumber", Toast.LENGTH_SHORT)
                    .show()
            } else {
                //Show message if floor number is out of range.
                val message = when {
                    floorNumber == null -> "Invalid floor number."
                    floorNumber < 1 -> "Floor number must be greater than 1."
                    floorNumber > 100 -> "Floor number must be less than 100."
                    else -> "Floor $floorNumber is not within valid range."
                }
                Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            //Show message when command doesn't match expected output.
            Toast.makeText(
                this,
                "Command is unrecognized. Try saying 'Go to floor X'",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    //Starting the voice recognition process.
    private fun startVoiceRec() {

        //Instance of the VoiceRecognizerHelper class.
        VoiceRecognizerHelper(application)

        //Creation og the intent variable.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to select a floor")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1000L
            )
        }

        try {
            voiceRecognitionLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Speech recognition is not supported.", Toast.LENGTH_LONG).show()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        voiceRecognizerHelper.destroy()
        bluetoothHelper.stopBluetoothDiscovery()
    }



}

    //Composable for the drop down menu.
    //COMPLETE & OPERATIONAL. 
    @Composable
    fun DropDown(onModeSelected: (String) -> Unit) {
        val list = listOf("Elevator Access", "Null") //List of modes for the drop down menu.
        var expandedState by
        remember { //Creating the state of the drop down menu being expanded.
            mutableStateOf(false)
        }
        var currentState by remember {
            mutableStateOf(list[0])
        } //Track selected mode.

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) { //Surface created.
            Box { //Box created.
                Row(modifier = Modifier
                    .clickable {
                        expandedState = !expandedState
                    }
                    .padding(8.dp)
                    .fillMaxWidth()) { //Row created.
                    Text(
                        text = currentState,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    ) //Display selected mode.
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                DropdownMenu(
                    expanded = expandedState,
                    onDismissRequest = { expandedState = false }) {
                    //Setting the text for the drop down menu.
                    list.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(text = mode) },
                            onClick = {
                                currentState =
                                    mode //Update the mode being selected from the dropdown menu.
                                expandedState =
                                    false //Close the dropdown menu whenever the mode is selected.
                                onModeSelected(mode) //Notify parent about mode selected
                            })
                    }
                }
            }

        }
    }

    //Composable for the blank mode that demonstrates a possibility of mode change.
    //COMPLETE & OPERATIONAL.
    @Composable
    fun BlankMode() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {}
    }

    //Composable for the bluetooth status indicator.
    //COMPLETE & OPERATIONAL.
    @Composable
    fun BluetoothStatusIndicator(isConnected: Boolean) {
        //Determine the status text and color based on connection status.
        val status =
            if (isConnected) "Bluetooth Connected" else "Bluetooth Disconnected" //Status text
        val statusColor = if (isConnected) Color.Green else Color.Red //Status color

        Row( //Create row.
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.baseline_bluetooth_24),
                contentDescription = "Bluetooth Status",
                tint = statusColor
            ) //Bluetooth icon with status color.
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = status,
                color = statusColor,
                style = MaterialTheme.typography.bodyLarge
            ) //Display connection status text.
        }
    }

    //Composable for the user interface with the device list and 'Discover Devices' button.
    //COMPLETE & OPERATIONAL.
    @RequiresApi(Build.VERSION_CODES.S)
    @Composable
    fun BluetoothUi() {
        val isBluetoothConnected by remember { mutableStateOf(false) }
        val discoveredDevices = remember { mutableStateListOf<BluetoothDevice>() }

        //Register receiver to listen for the Bluetooth connection change.
        val context = LocalContext.current

        //Create a bluetoothHelper object.
        lateinit var bluetoothHelper: BluetoothHelper


        DisposableEffect(context) {
            val bluetoothReceiver = object : BroadcastReceiver() {
                @RequiresApi(Build.VERSION_CODES.TIRAMISU)
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (context == null || intent == null) return

                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> handleDeviceFound(
                            intent,
                            context,
                            discoveredDevices
                        )

                        BluetoothAdapter.ACTION_DISCOVERY_STARTED -> showToast(
                            context,
                            "Bluetooth Discovery has started."
                        )

                        BluetoothDevice.ACTION_ACL_CONNECTED -> handleConnectionStatusChange(
                            context,
                            isConnected = true
                        )

                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> handleConnectionStatusChange(
                            context,
                            isConnected = false
                        )

                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> showToast(
                            context,
                            "Bluetooth Discovery has finished."
                        )
                    }
                }
            }


        //Register the receiver in the lifecycle Composition.
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(bluetoothReceiver, filter)

        onDispose {
            context.unregisterReceiver(bluetoothReceiver)
        }
    }

    //Displays the Bluetooth status indicator.
    BluetoothStatusIndicator(isConnected = isBluetoothConnected)

    Column(
    modifier = Modifier
    .fillMaxWidth()
    .padding(10.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
    )
    {
        val enableBluetoothLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
            onResult = { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    Log.d("Bluetooth", "Bluetooth enabled by user.")
                } else {
                    Log.d("Bluetooth", "Bluetooth enabling failed or was denied.")
                    Toast.makeText(context, "Bluetooth is required for discovery.", Toast.LENGTH_LONG).show()
                }
            }
        )

        DiscoverDevice(
            devices = discoveredDevices,
            hasPermissions = true, startDiscovery = {
                //Check is Bluetooth is turned on.
                bluetoothHelper.ensureBluetoothEnabledAndDiscover(enableBluetoothLauncher)
            }
        )
    }
}

        //Device list function helps display the list of connectable devices.
        //May still need work.
        @Composable
        fun DeviceList(
            devices: List<BluetoothDevice>,
            onDeviceClick: (BluetoothDevice) -> Unit,
            onDialogDismiss: () -> Unit,
            isDeviceConnected: Boolean
        ) {

            AlertDialog(
                onDismissRequest = { onDialogDismiss() },
                title = {
                    Text("Discovered Devices")
                },
                text = {
                    if (devices.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(devices) { device ->
                                DeviceItem(device = device, onClick = { onDeviceClick(device) })
                            }
                        }
                    } else if (!isDeviceConnected) {
                        Text("No devices found. Click Discover Devices to find nearby devices.")
                    }
                },
                confirmButton = {
                    Button(onClick = onDialogDismiss) {
                        Text("Close")
                    }
                }
            )
        }

        //Composable to display the device name and address for the device list pop-up.
        //May still need work.
        @Composable
        fun DeviceItem(
            device: BluetoothDevice,
            onClick: () -> Unit
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.baseline_bluetooth_24),
                    contentDescription = "Bluetooth Icon",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))

                val context = LocalContext.current

                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Text(
                        text = device.name ?: "Unknown Device",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = device.address ?: "Unknown",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        fun manageDataCommunications(socket: BluetoothSocket) {
            val inputStream = socket.inputStream
            socket.outputStream
            val isRunning = AtomicBoolean(true) //Flag for loop control.

            //Coroutine scope to manage communication.
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            // Shared error handler to stop communication gracefully
            fun handleError(exception: IOException?) {
                Log.e("Bluetooth", "Error occurred: ${exception?.message}", exception)
                if (isRunning.compareAndSet(
                        true,
                        false
                    )
                ) { // Ensure stopCommunication only runs once
                    scope.launch {
                        try {
                            socket.close() // Close the socket
                            Log.i("Bluetooth", "Socket closed.")
                        } catch (e: IOException) {
                            Log.e("Bluetooth", "Error closing socket: ${e.message}", e)
                        } finally {
                            scope.cancel() // Cancel the scope after cleanup
                        }
                    }
                }
            }

            //Receiving data.
            fun receiveData() {

                scope.launch {
                    val buffer = ByteArray(1024)

                    try {
                        while (isRunning.get()) {
                            val bytes =
                                inputStream.read(buffer) //Read the buffer into the number of bytes.
                            val message = String(buffer, 0, bytes, Charsets.UTF_8).trim()
                            Log.i("Bluetooth", "Received: $message")
                        }
                    } catch (e: IOException) {
                        handleError(e)
                    }
                }
            }

            receiveData()

        }

        //Composable for the 'Discover Devices' button.
        //Should be COMPLETE & OPERATIONAL.
        @RequiresApi(Build.VERSION_CODES.S)
        @Composable
        fun DiscoverDevice(
            devices: SnapshotStateList<BluetoothDevice>, //List of discovered devices.
            hasPermissions: Boolean, //Permission check for Bluetooth.
            startDiscovery: () -> Unit //Triggers Bluetooth discovery.
        ) {

            var showDeviceDialog by remember { mutableStateOf(false) }

            val context = LocalContext.current

            //Initialize Bluetooth adapter.
            val bluetoothAdapter = getBluetoothAdapter(context)

            //State that checks if Bluetooth is enabled.
            val isBluetoothEnabled =
                remember { mutableStateOf(bluetoothAdapter?.isEnabled == true) }

            //Monitor whether Bluetooth is enabled or not.
            DisposableEffect(Unit) {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                val receiver = getBluetoothReceiver(isBluetoothEnabled, startDiscovery)
                context.registerReceiver(receiver, filter)

                onDispose {
                    context.unregisterReceiver(receiver)
                }

            }

            //Button that triggers device
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                //Button that triggers discovery.
                Button(
                    onClick = {
                        showDeviceDialog = onDiscoverDevicesButtonClick(
                            bluetoothAdapter,
                            context,
                            hasPermissions,
                            startDiscovery
                        )
                    },
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text("Discover Devices")
                }

                if (showDeviceDialog) {
                    DeviceList(
                        devices = devices,
                        onDeviceClick = { device -> onDeviceClick(device, context) },
                        onDialogDismiss = { showDeviceDialog = false }, //Close the dialog.
                        isDeviceConnected = false
                    )
                }

                DisplayStatusMessages(devices, hasPermissions)

            }
        }

        private fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            return bluetoothManager.adapter
        }

        private fun getBluetoothReceiver(
            isBluetoothEnabled: MutableState<Boolean>,
            startDiscovery: () -> Unit
        ): BroadcastReceiver {
            return object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val state =
                        intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            isBluetoothEnabled.value = true
                            startDiscovery()
                        }

                        BluetoothAdapter.STATE_OFF -> isBluetoothEnabled.value = false
                    }
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun onDiscoverDevicesButtonClick(
            bluetoothAdapter: BluetoothAdapter?,
            context: Context,
            hasPermissions: Boolean,
            startDiscovery: () -> Unit,
        ): Boolean {
            return when {
                bluetoothAdapter?.isEnabled == false -> {
                    enableBluetooth(context)
                    false
                }

                hasPermissions -> {
                    startDiscovery()
                    true
                }

                else -> {
                    showToast(context, "Permissions are required for Bluetooth.")
                    false
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun enableBluetooth(context: Context) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Manifest.permission.BLUETOOTH_CONNECT
                    } else {
                        Manifest.permission.BLUETOOTH
                    }
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    context as Activity,
                    arrayOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Manifest.permission.BLUETOOTH_CONNECT
                    } else {
                        Manifest.permission.BLUETOOTH
                    }),
                    1001
                )
                showToast(context, "Permissions required to enable Bluetooth.")
            } else {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                context.startActivity(enableBtIntent)
            }
        }

        private fun onDeviceClick(device: BluetoothDevice, context: Context) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    context as Activity,
                    arrayOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Manifest.permission.BLUETOOTH_CONNECT
                    } else {
                        Manifest.permission.BLUETOOTH
                    }),
                    1001
                )
                showToast(context, "Permission required to connect.")
                return
            }

            connectToDevice(device, context)
        }

        private fun connectToDevice(device: BluetoothDevice, context: Context) {
            // Check if permission is granted
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                showToast(context, "Permission required to connect.")
                return
            }

            try {
                // Create an RFCOMM socket to the device
                val socket = device.createRfcommSocketToServiceRecord(MY_UUID)
                socket.connect()
                manageDataCommunications(socket)

                // Show a success message on the UI thread
                Handler(Looper.getMainLooper()).post {
                    showToast(context, "Connection successful with ${device.name}")
                }
            } catch (e: IOException) {
                e.printStackTrace()

                // Show a failure message if the connection fails
                Handler(Looper.getMainLooper()).post {
                    showToast(context, "Connection failed: ${e.message}")
                }
            }
        }


        @Composable
        fun DisplayStatusMessages(
            devices: SnapshotStateList<BluetoothDevice>,
            hasPermissions: Boolean
        ) {
            when {
                !hasPermissions -> Text("Bluetooth permissions are required to discover devices.")
                devices.isEmpty() -> Text("No devices found. Start discovery to find nearby devices.")
            }
        }

        private fun showToast(context: Context, message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

//Assists with displaying the devices in the device list pop-up.
        //COMPLETE.
        object BluetoothReceiver {

            private lateinit var deviceDiscoveryReceiver: BroadcastReceiver

            private lateinit var bluetoothAdapter: BluetoothAdapter

            private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main

            fun registerDiscoveryReceiver(
                context: Context,
                devices: MutableState<List<BluetoothDevice>>,
                bluetoothHelper: BluetoothHelper,
                lifecycleScope: LifecycleCoroutineScope
            ) {

                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bluetoothAdapter = bluetoothManager.adapter

                val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }

                deviceDiscoveryReceiver = object : BroadcastReceiver() {
                    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (context == null || intent?.action == null) return

                        when (intent.action) {
                            BluetoothDevice.ACTION_FOUND -> handleDeviceFound(
                                context,
                                intent,
                                devices
                            )

                            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> handleBondStateChanged(
                                context,
                                intent,
                                bluetoothHelper,
                                lifecycleScope
                            )

                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> handleDiscoveryFinished(
                                context
                            )
                        }
                    }
                }
                context.registerReceiver(deviceDiscoveryReceiver, filter)
            }

            //Unregisters the device from the list.
            //COMPLETE.
            fun unregisterDiscoveryReceiver(context: Context) {
                context.unregisterReceiver(deviceDiscoveryReceiver)
            }

            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            private fun handleDeviceFound(
                context: Context,
                intent: Intent,
                devices: MutableState<List<BluetoothDevice>>
            ) {
                val device: BluetoothDevice? = getBluetoothDeviceFromIntent(intent, BluetoothDevice::class.java)
                device?.let { bluetoothDevice ->
                    // Ensure permission before accessing Bluetooth device data
                    if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        showToast(context, "Bluetooth permission required to access device.")
                        return
                    }

                    // Add the device to the list if it's not already present
                    if (devices.value.none { it.address == bluetoothDevice.address }) {
                        devices.value += bluetoothDevice
                    }
                }
            }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun <T : Parcelable> getBluetoothDeviceFromIntent(intent: Intent, clazz: Class<T>): T? {
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, clazz)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun handleBondStateChanged(
                context: Context,
                intent: Intent,
                bluetoothHelper: BluetoothHelper,
                lifecycleScope: LifecycleCoroutineScope
            ) {
        val device: BluetoothDevice? = getBluetoothDeviceFromIntent(intent, BluetoothDevice::class.java)

        device?.let { bluetoothDevice ->

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                showToast(context, "Bluetooth permission required to connect.")
                return
            }

            when (bluetoothDevice.bondState) {
                BluetoothDevice.BOND_BONDED -> connectToBondedDevice(
                    context,
                    bluetoothDevice,
                    bluetoothHelper,
                    lifecycleScope
                )

                BluetoothDevice.BOND_NONE -> showToast(
                    context,
                    "Pairing failed with ${bluetoothDevice.name}."
                )
            }
        }
    }

        private fun connectToBondedDevice(
                context: Context,
                device: BluetoothDevice,
                bluetoothHelper: BluetoothHelper,
                lifecycleScope: LifecycleCoroutineScope
            ) {
                val deviceName = if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                    != PackageManager.PERMISSION_GRANTED
                ) "Unknown Device" else device.name

                showToast(context, "Paired with: $deviceName.")

                lifecycleScope.launch(mainDispatcher) {
                    delay(1000)
                    bluetoothHelper.connectToDevice(device) { isConnected, errorMessage ->
                        if (isConnected) {
                            showToast(context, "Connected to: $deviceName")
                        } else {
                            val message = errorMessage ?: "Failed to connect to: $deviceName"
                            showToast(context, message.toString())
                        }
                    }
                }
            }

            private fun handleDiscoveryFinished(context: Context) {
                showToast(context, "Bluetooth discovery finished.")
            }


            private fun showToast(context: Context, message: String) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        // This composable function will tell the microcontroller which elevator button the robotic arm should press based on user's interaction.
        // COMPLETE & OPERATIONAL.
        @Composable
        fun ElevatorButtons(bluetoothHelper: BluetoothHelper) {
            val context = LocalContext.current

            var floorNumber by remember { mutableIntStateOf(0) }

            val showDialog = remember { mutableStateOf(false) }

            //Function to send the command to the microcontroller via Bluetooth.
            fun sendCommand(command: String) {
                Toast.makeText(context, "Sending command: $command", Toast.LENGTH_SHORT).show()
                bluetoothHelper.sendCommand(command) { error ->
                    Toast.makeText(context, "Error sending command: $error", Toast.LENGTH_SHORT)
                        .show()
                }

            }


            @Composable
            fun ElevatorButton(label: String, floor: Int?) {
                Button(
                    onClick = {
                            if (floor != null) {
                                floorNumber = floor
                                showDialog.value = true
                        }else {
                            sendCommand(label.uppercase())
                        }
                    }
                ) {
                    Text(
                        modifier = Modifier.padding(8.dp),
                        text = label,
                        style = TextStyle(fontSize = 15.sp)
                    )
                }
            }


                Row(
                    horizontalArrangement = Arrangement.spacedBy(
                        40.dp,
                        Alignment.CenterHorizontally
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    //Creating the button for the up button.
                    ElevatorButton("Up", null)

                    //Creating the button for the down button.
                    ElevatorButton("Down", null)
                }

                Spacer(modifier = Modifier.height(20.dp))

                //Creating the first row of interior elevator buttons.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(
                        65.dp,
                        Alignment.CenterHorizontally
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    //Creating the button for button 1.
                    ElevatorButton("1", 1)

                    //Creating the button for button 2.
                    ElevatorButton("2", 2)

                    //Creating the button for button 3.
                    ElevatorButton("3", 3)
                }

                Spacer(modifier = Modifier.height(20.dp)) //Spacing out buttons 1, 2, and 3 with buttons 4, 5, and 6.

                Row(
                    horizontalArrangement = Arrangement.spacedBy(
                        65.dp,
                        Alignment.CenterHorizontally
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    //Creating the button for button 4.
                    ElevatorButton("4", 4)

                    //Creating the button for button 5.
                    ElevatorButton("5", 5)

                    //Creating the button for button 6.
                   ElevatorButton("6", 6)
                }

                Spacer(modifier = Modifier.height(20.dp)) //Spacing out buttons 4, 5, and 6 with buttons 7, 8, and 9.

                Row(
                    horizontalArrangement = Arrangement.spacedBy(
                        65.dp,
                        Alignment.CenterHorizontally
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    //Creating the button for button 7.
                    ElevatorButton("7", 7)

                    //Creating the button for button 8.
                    ElevatorButton("8", 8)

                    //Creating the button for button 9.
                    ElevatorButton("9", 9)
                }

            ConfirmationDialog(
                floorNumber = floorNumber,
                onConfirm = {
                    sendCommand("FLOOR_$floorNumber")
                },
                onDismiss = {

                },
                showDialog = showDialog
            )

            }


        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        fun handleDeviceFound(
            intent: Intent,
            context: Context,
            discoveredDevices: SnapshotStateList<BluetoothDevice>
        ) {
            // Check Bluetooth permission
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w("Bluetooth", "BLUETOOTH_CONNECT permission not granted.")
                return // Exit early if permission is not granted
            }

            val device: BluetoothDevice? =
                intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                )

            device?.let {
                Log.d(
                    "Bluetooth",
                    "Discovered device: ${it.name ?: "Unknown"} - ${it.address}"
                )

                // Add device only if it's not already in the list based on address
                if (discoveredDevices.none { existingDevice -> existingDevice.address == it.address }) {
                    discoveredDevices.add(it)
                }
            }
        }

        fun handleConnectionStatusChange(context: Context, isConnected: Boolean) {
            val message = if (isConnected) {
                "Bluetooth connection has been established."
            } else {
                "Bluetooth connection has failed."
            }

            showToast(context, message)
        }

