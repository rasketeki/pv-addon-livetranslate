package dev.livetranslate

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Minimal audio helpers, no external deps. */
object AudioUtil {

    /** Count silence at the end of a packet, including packets containing both speech and silence. */
    fun trailingSilenceSamples(samples: ShortArray, silencePeak: Int): Int {
        var count = 0
        for (i in samples.indices.reversed()) {
            if (abs(samples[i].toInt()) >= silencePeak) break
            count++
        }
        return count
    }

    // Windowed-sinc low-pass (cutoff ~7.2 kHz at 48 kHz) applied while
    // decimating by 3. Plain 3-sample averaging lets 8-24 kHz content alias
    // into the speech band, which audibly hurts whisper on consonants.
    private const val TAPS = 33
    private const val CUTOFF = 7200.0 / 48000.0

    private val FIR = DoubleArray(TAPS).also { h ->
        val center = (TAPS - 1) / 2
        var sum = 0.0
        for (i in 0 until TAPS) {
            val x = (i - center).toDouble()
            val sinc = if (i == center) 2.0 * CUTOFF else sin(2.0 * PI * CUTOFF * x) / (PI * x)
            val hamming = 0.54 - 0.46 * cos(2.0 * PI * i / (TAPS - 1))
            h[i] = sinc * hamming
            sum += h[i]
        }
        for (i in 0 until TAPS) h[i] /= sum // unity gain at DC
    }

    /** 48 kHz mono -> 16 kHz mono: FIR low-pass + decimate by 3. */
    fun downsample48to16(input: ShortArray): ShortArray {
        val outLen = input.size / 3
        val out = ShortArray(outLen)
        val center = (TAPS - 1) / 2
        for (i in 0 until outLen) {
            val base = i * 3
            var acc = 0.0
            for (k in 0 until TAPS) {
                val idx = base + k - center
                if (idx >= 0 && idx < input.size) acc += FIR[k] * input[idx]
            }
            out[i] = acc.roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * 16-bit PCM -> float [-1, 1] (whisper's sample format), peak-normalized.
     * Proximity voice is distance-attenuated, so far players arrive quiet and
     * whisper accuracy drops sharply on low-level audio. Gain is capped at 20x
     * so background noise isn't blown up to speech level.
     */
    fun pcm16ToFloat(pcm: ShortArray): FloatArray {
        var peak = 0
        for (s in pcm) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        val gain = if (peak > 0) (0.95f * 32768f / peak).coerceAtMost(20f) else 1f
        val out = FloatArray(pcm.size)
        for (i in pcm.indices) out[i] = (pcm[i] * gain / 32768.0f).coerceIn(-1f, 1f)
        return out
    }

}
