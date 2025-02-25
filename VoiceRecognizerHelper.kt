package com.example.brookselevatoraccess

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale


/**
 * Class intended to facilitate voice recognition.
 */
class VoiceRecognizerHelper(private val context: Context): RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerIntent: Intent? = null
    private var onCommandRecognized: ((String) -> Unit)? = null
    private var onErrorOccurred: ((String) -> Unit)? = null
    private var isListening = false

    private var handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private val _state = MutableStateFlow(VoiceToTextParseState())
    val state = _state.asStateFlow()

    init {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        speechRecognizer?.setRecognitionListener(this)
    }

    private fun resetTimeout() {
        //Remove pending timeouts.
        timeoutRunnable?.let { handler.removeCallbacks(it) }

            //Set a new timeout runnable that stops the recognition after 5 seconds.
            timeoutRunnable = Runnable {
                if (isListening) {
                    stopListening()
                    _state.update {
                        it.copy(isSpeaking = false)
                    }
                    Log.d(
                        "VoiceRecognition",
                        "No audio detected for five seconds, so the stop button is reverting to microphone."
                    )
                }
            }
            handler.postDelayed(timeoutRunnable!!, 5000)
        }

    fun startTimeout() {
        resetTimeout()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        _state.update {
            it.copy(
                error = null
            )
        }
        Log.d("VoiceRecognizer", "Ready for speech input.")
    }

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) {
        resetTimeout()
        Log.v("VoiceRecognizer", "RMS dB: $rmsdB.")
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        _state.update {
            it.copy(
                isSpeaking = false
            )
        }
            Log.d("VoiceRecognizer", "Speech input ended.")
    }

    //Called when an error has occurred.
    override fun onError(error: Int) {

        if (error == SpeechRecognizer.ERROR_CLIENT) {
            return
        }

        val errorMessage = when (error) {
            SpeechRecognizer.ERROR_NETWORK -> "Network Error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network Timeout Error"
            SpeechRecognizer.ERROR_SERVER -> "Server Error"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many speech requests"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Support cannot be checked"
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "Error occurred in listening to download events"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language not available for the speech recognizer"
            else -> "An error occurred during recognition. Please try again."
        }

        Log.e("VoiceRecognizer", "Error occurred: $errorMessage")
        _state.update {
            it.copy(
                error = errorMessage
            )
        }

        isListening = false

        Log.e("VoiceRecognizer", "Error: $errorMessage.")

        //Callback for errors:
        onErrorOccurred?.invoke(errorMessage)
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

        if (matches.isNullOrEmpty()) {
            Log.e("VoiceRecognizer", "No speech results recognized.")
            _state.update { it.copy(error = "No speech recognized", isSpeaking = false) }
            return
        }

        val recognizedText = matches[0]
        _state.update { it.copy(spokenText = recognizedText, isSpeaking = false) }

        Log.d("VoiceRecognizer", "Recognition results: $matches.")

        onCommandRecognized?.invoke(recognizedText) //Invoke the callback.

    }

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    /**
     * Initiates the app for listening to the user for a voice command.
     */
    fun startListening(languageCode: String = Locale.getDefault().language, callback: ((Boolean, String?) -> Unit)? = null) {
        isListening = true
        _state.update { VoiceToTextParseState() } //Resets state.

        // Check microphone permission
        if (ActivityCompat.checkSelfPermission(context.applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
           Log.e("VoiceRecognizer", "Mic permission is missing.")
            _state.update {
                it.copy(
                    error = "Microphone permission is not granted."
                )
            }
            Log.e("VoiceRecognizer", "Microphone permission is missing.")
            callback?.invoke(false, "Mic permission is not granted.")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context.applicationContext)) {
            Log.e("VoiceRecognizer", "Speech recognition is not available on this device.")
            _state.update {
                it.copy(
                    error = "Recognition is not available."
                )
            }
            callback?.invoke(false, "Recognition is not available.")
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext) ?: run {
                val tag = "Failed to create recognizer."
                Log.e("VoiceRecognizer", tag)
                _state.update { it.copy(error = tag) }
                callback?.invoke(false, tag)
                return
            }
            speechRecognizer?.setRecognitionListener(this)
        }

        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        Log.d("VoiceRecognizer", "Starting Speech Recognition.")

        try {
        speechRecognizer?.startListening(speechRecognizerIntent)
        _state.update { it.copy(isSpeaking = true) }
        Log.d("VoiceRecognizer", "Started listening for user input.")
        callback?.invoke(true, null)
        } catch (e:Exception) {
            Log.e("VoiceRecognizer", "Error starting speech recognition: ${e.message}.")
            _state.update {
                it.copy(error = "Error starting recognition: ${e.message}.")
            }
            callback?.invoke(false, "Error starting recognition: ${e.message}.")
        }
    }



    /**
     * Stops the app from listening for a user input.
     */
    fun stopListening(callback: (() -> Unit)? = null) {
        if (speechRecognizer != null) {
            _state.update {
                it.copy(
                    isSpeaking = false
                )
            }
            speechRecognizer?.stopListening()
            isListening = false
        }
        callback?.invoke()
    }

    fun setOnCommandRecognitionListener(listener: (String) -> Unit) {
        onCommandRecognized = listener
    }

    fun setOnErrorListener(listener: (String) -> Unit) {
        onErrorOccurred = listener
    }

    data class CommandResult(val floor: Int?, val direction: String?)

    /**
     * Function takes in a string from the user's command and converts it to an integer.
     */
    fun processCommand(command: String): List<Pair<Int?, String?>> {

        val regexPatterns = floorPatterns()

        val directionPatterns = mapOf(
            ".*(go|I want to go|take me|please go|going|).*.up.*" to "UP",
            ".*(go|I want to go|take me|please go|going|).*" to "DOWN",
        )

        val results = mutableListOf<Pair<Int?, String?>>()


        //Check for floor-specific commands and return the floorNumber without the direction-specific commands.
        for ((pattern, floorNumber) in regexPatterns) {
            if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(command)) {
                results.add(Pair(floorNumber, null))
            }
        }

        //Check for direction-specific commands, and return the direction without the floor number.
        for ((pattern, direction) in directionPatterns) {
            if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(command)) {
                results.add(Pair(null, direction))
            }
        }

        //Return null if no command matching occurred.
        return results.ifEmpty { listOf(Pair(null, null)) }

    }

    private fun floorPatterns(): Map<String, Int> {
        val floors = listOf(
            Pair(1, listOf("first", "one")),
            Pair(2, listOf("second", "two")),
            Pair(3, listOf("third", "three")),
            Pair(4, listOf("fourth", "four")),
            Pair(5, listOf("fifth", "five")),
            Pair(6, listOf("sixth", "six")),
            Pair(7, listOf("seventh", "seven")),
            Pair(8, listOf("eighth", "eight")),
            Pair(9, listOf("ninth", "nine"))
            //Add more floors as needed.
        )
        val patterns = mutableMapOf<String, Int>()
        val prefixes = listOf(
            "take me to", "please tale me to", "bring me to", "please bring me to",
            "go to", "please go to", "move to", "please move to"
        )

        for ((floor, synonyms) in floors) {
            for (synonym in synonyms) {
                patterns[".*(${prefixes.joinToString("|")}).*floor $synonym.*"] = floor
            }
        }
        return patterns
    }



   fun destroy() {
       Log.d("VoiceRecognizer", "Destroying VoiceRecognizer.")
       speechRecognizer?.destroy()
       speechRecognizer = null
       speechRecognizerIntent = null
   }




    data class VoiceToTextParseState(
        val spokenText: String = "",
        val isSpeaking: Boolean = false,
        val error: String? = null
    )

}