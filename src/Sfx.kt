package com.guarddog.dripfactory

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Generates tiny sound effects in code (no audio files needed). */
class Sfx(private val ctx: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = IntArray(5)

    init {
        try {
            val sounds = arrayOf(
                gen(Triple(700f, 260f, 80)),
                gen(Triple(660f, 660f, 90), Triple(880f, 880f, 90), Triple(1175f, 1175f, 200)),
                gen(Triple(500f, 900f, 120)),
                gen(Triple(400f, 600f, 90), Triple(600f, 900f, 90), Triple(900f, 1300f, 160)),
                gen(Triple(350f, 300f, 45))
            )
            for (i in sounds.indices) {
                val f = File(ctx.cacheDir, "sfx$i.wav")
                f.writeBytes(wav(sounds[i]))
                ids[i] = pool.load(f.absolutePath, 1)
            }
        } catch (e: Exception) {
            // sound is optional, ignore any problem
        }
    }

    fun play(index: Int, volume: Float = 1f) {
        val id = ids[index]
        if (id != 0) pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() {
        pool.release()
    }

    private fun gen(vararg segs: Triple<Float, Float, Int>): ShortArray {
        val rate = RATE
        val out = ArrayList<Short>()
        var phase = 0.0
        for ((f0, f1, ms) in segs) {
            val n = rate * ms / 1000
            for (i in 0 until n) {
                val t = i.toDouble() / n
                val f = f0.toDouble() + (f1 - f0).toDouble() * t
                phase += 2.0 * PI * f / rate
                val env = (1.0 - t).pow(1.5)
                val att = min(1.0, i / (rate * 0.004))
                val v = (sin(phase) * env * att * 0.5 * 32767.0).toInt()
                out.add(v.toShort())
            }
        }
        return ShortArray(out.size) { out[it] }
    }

    private fun wav(samples: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + samples.size * 2)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort())
        bb.putShort(1.toShort())
        bb.putInt(RATE)
        bb.putInt(RATE * 2)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put("data".toByteArray())
        bb.putInt(samples.size * 2)
        for (s in samples) bb.putShort(s)
        return bb.array()
    }

    companion object {
        const val PLOP = 0
        const val SELL = 1
        const val BUY = 2
        const val MERGE = 3
        const val TAP = 4
        private const val RATE = 22050
    }
}
