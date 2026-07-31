package dev.rpghelper.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * What the microphone control should be, right now.
 *
 * A state rather than a boolean because the three reasons it might not be usable have three
 * different remedies, and one of them is *no remedy at all* — a device with no on-device
 * recognizer cannot be talked into having one, so the control is **hidden** rather than
 * shown-and-broken.
 */
sealed interface VoiceState {
    /**
     * No on-device recognizer. The control is not shown.
     *
     * `03-model-runtime-spec.md` §3.1: the platform's default path may route audio to a
     * server, and **an app whose premise is that nothing leaves the device cannot ship a
     * microphone that quietly uploads what people say at their table.** The test is whether
     * offline operation can be *asserted*, not whether it usually happens — so anything
     * short of an on-device recognizer this API can name is this state.
     */
    data class Unavailable(val reason: String) : VoiceState

    /** Available, but the user has not granted the microphone. Asking is the remedy. */
    object NeedsPermission : VoiceState

    object Idle : VoiceState

    object Listening : VoiceState

    data class Failed(val reason: String) : VoiceState
}

/**
 * The rule, as a pure function, because it is the part worth testing.
 *
 * Kept out of the recognizer wrapper so that "is the microphone offered?" can be asserted
 * without a device, an audio stack, or a permission dialog.
 */
fun voiceState(onDeviceAvailable: Boolean, permissionGranted: Boolean): VoiceState = when {
    !onDeviceAvailable -> VoiceState.Unavailable(
        "This device has no on-device speech recognizer. Voice is not offered rather than " +
            "sent to a server — nothing this app hears leaves the device.",
    )
    !permissionGranted -> VoiceState.NeedsPermission
    else -> VoiceState.Idle
}

/**
 * Audio to text, **on the device or not at all**.
 *
 * `Transcriber` in `:model` is the seam this fills — it existed with no implementation and
 * no caller, which meant the microphone the spec describes had nothing behind it. This does
 * not implement that interface, because Android's recognizer is a callback API over a live
 * microphone rather than a function from a buffer: `AudioBuffer → String` would mean
 * recording audio into memory first and handing it to a recognizer that wants to stream. The
 * interface stays for a bundled ASR model, which is what it was written for; this is the
 * platform path the spec permits *if and only if* offline operation can be asserted.
 */
class OnDeviceVoice(private val context: Context) {

    /**
     * Whether this device can recognize speech without a network.
     *
     * `isOnDeviceRecognitionAvailable` arrived in API 31. Below that there is no way to
     * *assert* offline recognition — `EXTRA_PREFER_OFFLINE` is a preference, and a
     * preference that is silently ignored is exactly what §3.1 refuses to rely on — so the
     * honest answer for an older device is no.
     */
    fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * Listens once and calls back with what was heard.
     *
     * Returns a handle that cancels; the caller owns it for exactly as long as the control
     * is in [VoiceState.Listening]. `EXTRA_PREFER_OFFLINE` is set *as well as* using the
     * on-device recognizer — belt and braces, because the cost of the preference being
     * honoured is nothing and the cost of it being ignored is the premise of the app.
     */
    fun listen(onResult: (String) -> Unit, onFailure: (String) -> Unit): AutoCloseable {
        if (!onDeviceAvailable()) {
            onFailure("no on-device recognizer")
            return AutoCloseable {}
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val heard = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (heard.isBlank()) onFailure("nothing was heard") else onResult(heard)
                recognizer.destroy()
            }

            override fun onError(error: Int) {
                onFailure(describe(error))
                recognizer.destroy()
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            },
        )
        return AutoCloseable { runCatching { recognizer.destroy() } }
    }

    /** Sentences, not error codes. A user cannot act on `ERROR_NO_MATCH`. */
    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "that was not recognized as speech"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "nothing was said"
        SpeechRecognizer.ERROR_AUDIO -> "the microphone could not be read"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "the microphone is not permitted"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "the recognizer is busy"
        else -> "voice input failed"
    }
}
