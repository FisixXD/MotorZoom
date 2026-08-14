package app.motorzoom

object NativeNtsc {
    init { System.loadLibrary("ntsc_android") }

    external fun configure(presetJson: String): Boolean
    external fun defaultPreset(): String
    external fun normalizePreset(presetJson: String): String?
    external fun processRgba(
        pixels: ByteArray,
        width: Int,
        height: Int,
        frameNumber: Int
    ): Boolean
}
