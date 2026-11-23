package com.fearmikey.camera.domain.model

/** Symbologies the on-device decoder (OpenCV objdetect) can report. */
enum class CodeFormat(val label: String) {
    QR_CODE("QR code"),
    EAN_13("EAN-13"),
    EAN_8("EAN-8"),
    UPC_A("UPC-A"),
    UPC_E("UPC-E"),
    BARCODE("Barcode");

    /** Retail product numbers (GTIN family). */
    val isProduct: Boolean get() = this == EAN_13 || this == EAN_8 || this == UPC_A || this == UPC_E

    companion object {
        /** Maps OpenCV's type strings ("QR_CODE", "EAN_13", "UPC_A", ...). */
        fun fromNative(name: String): CodeFormat = when (name.uppercase().replace('-', '_')) {
            "QR_CODE", "QR" -> QR_CODE
            "EAN_13" -> EAN_13
            "EAN_8" -> EAN_8
            "UPC_A" -> UPC_A
            "UPC_E" -> UPC_E
            else -> BARCODE
        }
    }
}

/**
 * A decoded QR code or barcode.
 *
 * @param corners Four (x, y) corners, normalized 0..1 to the upright frame or image the code
 *   was found in (8 values), or empty when the decoder didn't report a location.
 */
data class ScannedCode(
    val text: String,
    val format: CodeFormat,
    val corners: List<Float> = emptyList(),
) {
    /** Parsed meaning of [text] (not part of equals/hashCode). */
    val payload: CodePayload by lazy { CodePayload.parse(text, format) }
}
