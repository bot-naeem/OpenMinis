package com.openminis.app.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Android VoiceInteractionSessionService producing overlay sessions when
 * the assistant is triggered by hardware key, gesture, or intent.
 */
class MinisVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return MinisVoiceInteractionSession(this)
    }
}
