package app.ghostline

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

// Puts the phone in call mode and routes call audio to the loudspeaker or earpiece.
// Main thread only.
@Suppress("DEPRECATION")
class CallAudio(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    var speaker = true
        private set

    fun start() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
    }

    fun toggleSpeaker(): Boolean {
        speaker = !speaker
        route()
        return speaker
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun route() {
        if (Build.VERSION.SDK_INT >= 31) {
            val type = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            am.availableCommunicationDevices.firstOrNull { it.type == type }?.let { am.setCommunicationDevice(it) }
        } else {
            am.isSpeakerphoneOn = speaker
        }
    }
}
