package tech.shroyer.q25trackpadcustomizer

enum class CursorSensitivity(val prefValue: Int, val pointerSpeed: Int) {
    SLOW(0, 1),
    MEDIUM(1, 3),
    FAST(2, 5);

    companion object {
        fun fromPrefValue(value: Int): CursorSensitivity {
            return when (value) {
                0 -> SLOW
                1 -> MEDIUM
                2 -> FAST
                else -> MEDIUM
            }
        }
    }
}
