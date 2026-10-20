package com.fishaudio.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** Android's data-check protocol describes local readiness, never a successful network authentication. */
class TtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT) {
            setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
                "This is a sample of Fish Audio text to speech."))
        } else {
            val configured = runCatching { ConfigurationStore(this).read().configured }.getOrDefault(false)
            val names = VoiceCatalog.dataNames()
            val data = Intent().putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                if (configured) names else arrayListOf())
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                    if (configured) arrayListOf() else names)
            setResult(if (configured) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL, data)
        }
        finish()
    }
}

/** Network voices have no offline data package. The install action opens credential/voice configuration. */
class InstallTtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
