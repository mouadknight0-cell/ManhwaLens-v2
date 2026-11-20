package com.manhwalens.app.diagnostics

/**
 * Stable, documented error codes. Every diagnostic event carries one.
 * Ranges:
 *  1xxx application lifecycle
 *  2xxx capture session (MediaProjection)
 *  3xxx image processing & OCR
 *  4xxx text reconstruction & grouping
 *  5xxx translation
 *  6xxx Arabic formatting & rendering / overlay
 *  7xxx storage, history, export, benchmark
 */
object ErrorCodes {
    // 1xxx Application lifecycle
    const val APP_INFO = "APP-1000"
    const val APP_SESSION_END = "APP-1005"
    const val APP_START_FAILED = "APP-1001"
    const val APP_INIT_DIAGNOSTICS_FAILED = "APP-1002"
    const val APP_UNCAUGHT_EXCEPTION = "APP-1003"
    const val APP_ONCREATE_FAILED = "APP-1004"

    // 2xxx Capture session
    const val CAPTURE_CONSENT_LAUNCH_FAILED = "CAP-2001"
    const val CAPTURE_PERMISSION_DENIED = "CAP-2002"
    const val CAPTURE_RESULT_MISSING = "CAP-2003"
    const val CAPTURE_SERVICE_START_FAILED = "CAP-2004"
    const val CAPTURE_FOREGROUND_START_FAILED = "CAP-2005"
    const val CAPTURE_MEDIA_PROJECTION_NULL = "CAP-2006"
    const val CAPTURE_MEDIA_PROJECTION_EXCEPTION = "CAP-2007"
    const val CAPTURE_VIRTUAL_DISPLAY_FAILED = "CAP-2008"
    const val CAPTURE_IMAGEREADER_FAILED = "CAP-2009"
    const val CAPTURE_NO_FRAME = "CAP-2010"
    const val CAPTURE_IMAGE_CONVERT_FAILED = "CAP-2011"
    const val CAPTURE_SESSION_EXPIRED = "CAP-2012"
    const val CAPTURE_INTERRUPTED = "CAP-2013"
    const val CAPTURE_TIMEOUT = "CAP-2014"
    const val CAPTURE_DISPLAY_METRICS_FAILED = "CAP-2015"

    // 3xxx Image processing & OCR
    const val OCR_INIT_FAILED = "OCR-3001"
    const val OCR_EXEC_FAILED = "OCR-3002"
    const val OCR_EMPTY = "OCR-3003"
    const val OCR_DECODE_FAILED = "OCR-3004"

    // 4xxx Text reconstruction
    const val TEXT_SPLIT_FAILED = "TXT-4001"
    const val TEXT_GROUPING_FAILED = "TXT-4002"
    const val TEXT_EMPTY_RECONSTRUCTED = "TXT-4003"

    // 5xxx Translation
    const val TRANSLATE_MODEL_UNAVAILABLE = "TRN-5001"
    const val TRANSLATE_DOWNLOAD_FAILED = "TRN-5002"
    const val TRANSLATE_FAILED = "TRN-5003"
    const val TRANSLATE_EMPTY = "TRN-5004"
    const val TRANSLATE_TIMEOUT = "TRN-5005"

    // 6xxx Arabic formatting / rendering / overlay
    const val UI_UPDATE_FAILED = "UI-6001"
    const val OVERLAY_PERMISSION_MISSING = "UI-6002"
    const val RTL_RENDER_FAILED = "UI-6004"

    // 7xxx Storage / export / benchmark
    const val HISTORY_WRITE_FAILED = "STO-7001"
    const val DIAGNOSTICS_EXPORT_FAILED = "STO-7002"
    const val BENCHMARK_RECORD_FAILED = "STO-7003"
    const val GLOSSARY_WRITE_FAILED = "STO-7004"
}
