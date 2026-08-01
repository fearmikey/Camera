package com.fearmikey.camera.domain.model

data class UserPreferences(
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    val videoResolution: VideoResolution = VideoResolution.RES_1080P,
    val imageFormat: ImageFormat = ImageFormat.JPEG,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val showGridLines: Boolean = false,
    val showLeveler: Boolean = false,
    val locationTagging: Boolean = false,
    val saveProSettings: Boolean = false
)

enum class AspectRatio(val value: String) {
    RATIO_4_3("4:3"),
    RATIO_16_9("16:9"),
    RATIO_FULL("Full")
}

enum class VideoResolution(val value: String) {
    RES_1080P("1080p"),
    RES_4K("4K")
}

enum class ImageFormat(val value: String) {
    JPEG("JPEG"),
    HEIF("HEIF")
}

enum class VideoCodec(val value: String) {
    H264("H.264"),
    HEVC("HEVC"),
    AV1("AV1")
}
