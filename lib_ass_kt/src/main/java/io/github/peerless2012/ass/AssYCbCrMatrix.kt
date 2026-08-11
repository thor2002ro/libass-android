package io.github.peerless2012.ass

/** The ASS track `YCbCr Matrix` header exposed by libass. */
enum class AssYCbCrMatrix(val nativeValue: Int) {
    DEFAULT(0),
    UNKNOWN(1),
    NONE(2),
    BT601_TV(3),
    BT601_PC(4),
    BT709_TV(5),
    BT709_PC(6),
    SMPTE240M_TV(7),
    SMPTE240M_PC(8),
    FCC_TV(9),
    FCC_PC(10),
    ;

    companion object {
        fun fromNative(value: Int): AssYCbCrMatrix =
            values().firstOrNull { it.nativeValue == value } ?: UNKNOWN
    }
}
