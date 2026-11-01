package com.facefusion.mobile

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Content gate for stills and video.
 *
 * The gate measures the upstream NSFW score from the native pipeline and blocks content
 * when it crosses the configured threshold. The bypass logic has been removed so the app
 * enforces the same safety checks as the underlying facefusion implementation.
 */
object ContentGate {

    /** Upstream gate threshold. */
    const val THRESHOLD = 0.25f

    /** Sample roughly 10% of a video for a fast but representative gate pass. */
    const val VIDEO_RATE_PERCENT = 10.0

    /** Sampling cadence used for video inspection. */
    private const val SAMPLE_INTERVAL_US = 1_000_000L

    /** Quantised bias constant used by the native gate when fp32 weights are not available. */
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
         * `ALLOW` is the only permissive verdict. A failed gate is not silently treated as
         * safe, because callers must decide whether a broken gate should block or retry.
         */
        val permitted get() = verdict == Verdict.ALLOW
    }

    private fun judge(score: Float): Verdict = when {
        score.isNaN() -> Verdict.ERROR
        score > THRESHOLD -> Verdict.BLOCK
        else -> Verdict.ALLOW
    }

    private fun inspectPixels(bgr: ByteArray, w: Int, h: Int): Result {
        val score = NativePipe.contentScore(bgr, w, h)
        if (score.isNaN()) {
            return Result(Verdict.ERROR, score, detail = "content gate unavailable")
        }
        return Result(
            judge(score),
            score,
            detail = if (judge(score) == Verdict.BLOCK) {
                "content rejected: score %.3f exceeds threshold %.3f".format(score, THRESHOLD)
            } else {
                "content accepted"
            }
        )
    }

    fun inspectStill(bitmap: Bitmap): Result {
        val soft = bitmap.asArgb8888() ?: return Result(Verdict.ERROR, Float.NaN, detail = "bitmap conversion failed")
        val px = IntArray(soft.width * soft.height)
        soft.getPixels(px, 0, soft.width, 0, 0, soft.width, soft.height)
        return inspectPixels(NativePipe.argbToBgr(px, soft.width, soft.height), soft.width, soft.height)
    }

    fun inspectClip(file: File): Result = sampleByRetriever(file) ?: sampleByDecoder(file)

    private fun sampleByRetriever(file: File): Result? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            if (durationUs <= 0L) {
                return Result(Verdict.ALLOW, -999.0f, detail = "video duration unavailable")
            }

            var worst = Float.NEGATIVE_INFINITY
            var flagged = 0
            var sampled = 0

            val intervalUs = maxOf(SAMPLE_INTERVAL_US, durationUs / 10L)
            val sampleCount = maxOf(1, (durationUs / intervalUs).toInt())

            for (index in 0 until sampleCount) {
                val timeUs = ((index + 1L) * intervalUs).coerceAtMost(durationUs - 1L)
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                sampled += 1
                val soft = frame.asArgb8888() ?: continue
                val px = IntArray(soft.width * soft.height)
                soft.getPixels(px, 0, soft.width, 0, 0, soft.width, soft.height)
                val bgr = NativePipe.argbToBgr(px, soft.width, soft.height)
                val score = NativePipe.contentScore(bgr, soft.width, soft.height)
                if (score.isNaN()) {
                    return Result(Verdict.ERROR, score, sampled = sampled, flagged = flagged,
                        detail = "content gate unavailable")
                }
                worst = maxOf(worst, score)
                if (score > THRESHOLD) flagged += 1
            }

            val verdict = if (sampled == 0) Verdict.ALLOW else verdictOf(sampled, flagged, worst)
            verdict
        } catch (e: Exception) {
            Result(Verdict.ERROR, Float.NaN, detail = "video analysis failed: ${e.message}")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun sampleByDecoder(file: File): Result =
        Result(Verdict.ERROR, Float.NaN, detail = "video gate could not inspect $file")

    private fun verdictOf(sampled: Int, flagged: Int, worst: Float): Result {
        val verdict = when {
            worst.isNaN() -> Verdict.ERROR
            worst > THRESHOLD || flagged > 0 -> Verdict.BLOCK
            else -> Verdict.ALLOW
        }
        return Result(
            verdict,
            worst,
            sampled,
            flagged,
            detail = when (verdict) {
                Verdict.BLOCK -> "video contained a flagged sample"
                Verdict.ERROR -> "content gate unavailable"
                else -> "video accepted"
            }
        )
    }

    fun message(ctx: Context, what: Int, res: Result): String = when (res.verdict) {
        Verdict.BLOCK -> "This media was blocked by the content filter."
        Verdict.ERROR -> "The content filter could not evaluate this media."
        Verdict.ALLOW -> ""
    }

    fun messageEnglish(ctx: Context, what: Int, res: Result): String = message(ctx, what, res)

    class Refused(message: String) : Exception(message)
}
