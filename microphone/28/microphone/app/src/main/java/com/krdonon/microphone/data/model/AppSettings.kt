package com.krdonon.microphone.data.model

enum class AudioQuality(val bitrate: Int, val sampleRate: Int, val label: String) {
    HIGH(256000, 48000, "고음질 256kbps, 48kHz"),
    MEDIUM(128000, 48000, "중간 128kbps, 48kHz"),
    LOW(64000, 48000, "저음질 64kbps, 48kHz")
}

enum class MicrophonePosition {
    TOP,    // 상단 마이크
    BOTTOM  // 하단 마이크
}

enum class StorageLocation {
    INTERNAL,
    SD_CARD,
    OTG
}

enum class AudioFormat(val extension: String) {
    M4A("m4a"),
    MP3("mp3")
}

data class AppSettings(
    val audioQuality: AudioQuality = AudioQuality.MEDIUM,
    val stereoRecording: Boolean = false,
    val blockCallsWhileRecording: Boolean = false,
    val autoPlayNext: Boolean = false,
    val storageLocation: StorageLocation = StorageLocation.INTERNAL,
    val microphonePosition: MicrophonePosition = MicrophonePosition.BOTTOM,
    val audioFormat: AudioFormat = AudioFormat.M4A,
    val useBluetoothMic: Boolean = false
)
