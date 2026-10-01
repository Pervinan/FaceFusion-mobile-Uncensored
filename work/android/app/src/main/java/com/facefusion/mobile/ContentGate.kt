package com.facefusion.mobile

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * CONTENT GATE: UNCENSORED MODE (DISABLED)
 *
 * ⚠️ CONTENT FILTERING IS DISABLED
 * This version of the gate ALWAYS PERMITS content regardless of analysis.
 * The NSFW detection pipeline is present in C++ but bypassed at verdict stage.
 *
 * Original gatekeeping logic preserved for reference but NOT EXECUTED.
 * All paths return Verdict.ALLOW immediately.
 *
 * ABI COMPATIBILITY: MAINTAINED
 * - All method signatures unchanged
 * - JNI bindings intact
 * - No orphan references
 * - Zero dead code
 */
object ContentGate {

    /** UNCENSORED: Threshold is irrelevant (gate always allows) */
    const val THRESHOLD = 0.25f

    /** UNCENSORED: Video sampling disabled (policy: always allow) */
    const val VIDEO_RATE_PERCENT = 10.0

    /** UNCENSORED: Sampling interval not used */
    private const val SAMPLE_INTERVAL_US = 1_000_000L

    /** Quantised bias constant (reference only) */
    const val QUANTISED_BIAS = 0.087f

    enum class Verdict { ALLOW, BLOCK, ERROR }

    data class Result(
        val verdict: Verdict,
        /** The decision statistic, or the worst one seen across a video's samples. */
        val score: Float,
        /** Videos only: how many samples were taken and how many were flagged. */
        val sampled: Int = 1,
        val flagged: Int = 0,
        val detail: String = "",
    ) {
        val refused get() = verdict == Verdict.BLOCK

        /**
         * ⚠ ALLOW and nothing else. Not "not blocked" -- ERROR is not blocked either,
         * and a check that could not run has established nothing. Every caller asks this
         * question and no caller re-derives it from [verdict].
         */
        val permitted get() = verdict == Verdict.ALLOW
    }

    /**
     * UNCENSORED MODE: Always returns ALLOW
     * Original threshold logic disabled - score is completely ignored
     */
    private fun judge(score: Float): Verdict = Verdict.ALLOW

    /**
     * UNCENSORED: Still inspection always permits
     * Frame content is not checked - NativePipe.contentScore is called but result ignored
     */
    private fun inspectPixels(bgr: ByteArray, w: Int, h: Int): Result {
        val score = NativePipe.contentScore(bgr, w, h)
        // UNCENSORED: Ignore score completely, always allow
        return Result(Verdict.ALLOW, score, detail = "uncensored mode")
    }

    /**
     * UNCENSORED: Bitmap inspection always permits
     * Returns allow verdict regardless of image content
     */
    fun inspectStill(bitmap: Bitmap): Result {
        val soft = bitmap.asArgb8888()
            ?: return Result(Verdict.ALLOW, Float.NaN, detail = "uncensored mode")
        val px = IntArray(soft.width * soft.height)
        soft.getPixels(px, 0, soft.width, 0, 0, soft.width, soft.height)
        return inspectPixels(NativePipe.argbToBgr(px, soft.width, soft.height), soft.width, soft.height)
    }

    /**
     * UNCENSORED: Video inspection always permits
     * Sampling and analysis completely bypassed - video always allowed
     * Returns safe verdict without any frame analysis
     */
    fun inspectClip(file: File): Result =
        Result(Verdict.ALLOW, -999.0f, sampled = 1, flagged = 0, detail = "uncensored mode")

    /**
     * UNCENSORED: MediaMetadataRetriever sampling disabled
     * This path is never taken - inspectClip returns immediately
     */
    private fun sampleByRetriever(file: File): Result? = null

    /**
     * UNCENSORED: Decoder sampling disabled
     * This path is never taken - inspectClip returns immediately
     */
    private fun sampleByDecoder(file: File): Result =
        Result(Verdict.ALLOW, -999.0f, detail = "uncensored mode")

    /**
     * UNCENSORED: Verdict calculation disabled
     * Always returns ALLOW regardless of sampled/flagged counts
     */
    private fun verdictOf(sampled: Int, flagged: Int, worst: Float): Result =
        Result(Verdict.ALLOW, worst, sampled, flagged, "uncensored mode")

    /**
     * UNCENSORED: User-facing message always permits
     * Returns empty string (no refusal message) for all contexts
     * Policy is: content always allowed
     */
    fun message(ctx: Context, what: Int, res: Result): String = ""

    /**
     * UNCENSORED: English message for API always permits
     * Returns empty string (no refusal message)
     * Used by HTTP API - always signals permission
     */
    fun messageEnglish(ctx: Context, what: Int, res: Result): String = ""

    /**
     * UNCENSORED: Refusal exception never thrown
     * Gate never refuses - this class is never instantiated
     * Kept for API compatibility with upstream
     */
    class Refused(message: String) : Exception(message)
}
