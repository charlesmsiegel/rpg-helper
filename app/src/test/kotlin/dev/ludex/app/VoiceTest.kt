package dev.ludex.app

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The microphone rule, which is a privacy decision wearing a UI decision's clothes.
 *
 * `03-model-runtime-spec.md` §3.1: the platform's default recognition path may route audio
 * to a server, and **an app whose premise is that nothing leaves the device cannot ship a
 * microphone that quietly uploads what people say at their table.** So the control is
 * hidden — not disabled, not shown-with-a-warning — unless offline recognition can be
 * *asserted*. A disabled microphone is a promise the app is refusing to keep; an absent one
 * is a statement about what this build will not do.
 */
class VoiceTest {

    @Test
    fun `no on-device recognizer means the control is not offered at all`() {
        val state = voiceState(onDeviceAvailable = false, permissionGranted = false)
        assertTrue(state is VoiceState.Unavailable, "got $state")
        assertTrue(
            "leaves the device" in state.reason,
            "the reason has to say why, or it reads as a bug: ${state.reason}",
        )
    }

    @Test
    fun `granting the microphone does not conjure a recognizer`() {
        // The permission is not the question. A device that would send audio to a server is
        // one this app declines to use the microphone on, however permitted it is.
        assertTrue(
            voiceState(onDeviceAvailable = false, permissionGranted = true) is VoiceState.Unavailable,
        )
    }

    @Test
    fun `a device that can transcribe locally is asked for the microphone`() {
        assertEquals(
            VoiceState.NeedsPermission,
            voiceState(onDeviceAvailable = true, permissionGranted = false),
        )
    }

    @Test
    fun `with both, the control is live`() {
        assertEquals(VoiceState.Idle, voiceState(onDeviceAvailable = true, permissionGranted = true))
    }
}
