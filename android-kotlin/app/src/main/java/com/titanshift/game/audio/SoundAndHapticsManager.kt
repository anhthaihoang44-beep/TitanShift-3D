package com.titanshift.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.titanshift.game.model.GameEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class SoundAndHapticsManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var soundEnabled: Boolean = true

    @Volatile
    var hapticsEnabled: Boolean = true

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun trigger(event: GameEvent) {
        if (hapticsEnabled) {
            triggerHaptic(event)
        }
        if (soundEnabled) {
            scope.launch {
                playProceduralSound(event)
            }
        }
    }

    private fun triggerHaptic(event: GameEvent) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        runCatching {
            val effect = when (event) {
                GameEvent.SCALE_ZONE_TICK -> VibrationEffect.createOneShot(12L, 55)
                GameEvent.COIN_PICKUP -> VibrationEffect.createOneShot(10L, 40)
                GameEvent.SLIP_UNDER, GameEvent.GLASS_SAFE, GameEvent.GIANT_LEAP ->
                    VibrationEffect.createOneShot(22L, 95)
                GameEvent.PERFECT_SYNC, GameEvent.SHIELD_GAIN ->
                    VibrationEffect.createOneShot(30L, 140)
                GameEvent.WALL_SMASH, GameEvent.RIVAL_CRUSH, GameEvent.MULTIPLIER_BREAK, GameEvent.BOSS_CLASH_HIT ->
                    VibrationEffect.createOneShot(48L, 220)
                GameEvent.OVERDRIVE_ACTIVATED, GameEvent.STAGE_VICTORY ->
                    VibrationEffect.createWaveform(longArrayOf(0, 45, 35, 70), intArrayOf(0, 180, 0, 255), -1)
                GameEvent.GLASS_SHATTER, GameEvent.SHIELD_BLOCK, GameEvent.PLAYER_DEFEAT ->
                    VibrationEffect.createWaveform(longArrayOf(0, 70, 40, 95), intArrayOf(0, 240, 0, 200), -1)
            }
            vib.vibrate(effect)
        }
    }

    private fun playProceduralSound(event: GameEvent) {
        val sampleRate = 22050
        val pcm: ShortArray = when (event) {
            GameEvent.SCALE_ZONE_TICK -> synthTone(sampleRate, 0.035f, 520f, 680f, noiseMix = 0f, volume = 0.16f)
            GameEvent.COIN_PICKUP -> synthDualChime(sampleRate, 880f, 1320f, 0.09f, volume = 0.25f)
            GameEvent.SHIELD_GAIN -> synthDualChime(sampleRate, 587f, 1174f, 0.18f, volume = 0.30f)
            GameEvent.SLIP_UNDER, GameEvent.GLASS_SAFE -> synthTone(sampleRate, 0.12f, 420f, 940f, noiseMix = 0.08f, volume = 0.28f)
            GameEvent.GIANT_LEAP -> synthTone(sampleRate, 0.16f, 220f, 660f, noiseMix = 0.05f, volume = 0.32f)
            GameEvent.PERFECT_SYNC -> synthArpeggio(sampleRate, floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f), 0.055f, 0.32f)
            GameEvent.WALL_SMASH, GameEvent.RIVAL_CRUSH, GameEvent.MULTIPLIER_BREAK ->
                synthTone(sampleRate, 0.18f, 165f, 58f, noiseMix = 0.55f, volume = 0.42f)
            GameEvent.BOSS_CLASH_HIT ->
                synthTone(sampleRate, 0.11f, 240f, 110f, noiseMix = 0.38f, volume = 0.36f)
            GameEvent.GLASS_SHATTER, GameEvent.SHIELD_BLOCK ->
                synthTone(sampleRate, 0.22f, 1400f, 380f, noiseMix = 0.72f, volume = 0.36f)
            GameEvent.OVERDRIVE_ACTIVATED ->
                synthArpeggio(sampleRate, floatArrayOf(440f, 554.37f, 659.25f, 880f, 1108.7f), 0.055f, 0.36f)
            GameEvent.STAGE_VICTORY ->
                synthArpeggio(sampleRate, floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f, 1318.5f), 0.075f, 0.38f)
            GameEvent.PLAYER_DEFEAT ->
                synthArpeggio(sampleRate, floatArrayOf(392f, 349.23f, 311.13f, 261.63f), 0.09f, 0.36f)
        }

        runCatching {
            val byteSize = pcm.size * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(byteSize.coerceAtLeast(1024))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep((pcm.size * 1000L / sampleRate) + 40L)
            track.stop()
            track.release()
        }
    }

    private fun synthTone(
        sampleRate: Int,
        durationSec: Float,
        fStart: Float,
        fEnd: Float,
        noiseMix: Float,
        volume: Float
    ): ShortArray {
        val count = (sampleRate * durationSec).toInt().coerceAtLeast(64)
        val out = ShortArray(count)
        var phase = 0.0
        val rng = Random(1337)
        for (i in 0 until count) {
            val t = i.toFloat() / count
            val freq = fStart + (fEnd - fStart) * t
            phase += 2.0 * PI * freq / sampleRate
            val env = exp(-3.5 * t.toDouble()).toFloat()
            val tone = (sin(phase) * 0.75 + sin(phase * 2.0) * 0.25).toFloat()
            val noise = (rng.nextFloat() * 2f - 1f)
            val mixed = (tone * (1f - noiseMix) + noise * noiseMix) * env * volume
            out[i] = (mixed.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun synthDualChime(
        sampleRate: Int,
        f1: Float,
        f2: Float,
        durationSec: Float,
        volume: Float
    ): ShortArray {
        val count = (sampleRate * durationSec).toInt().coerceAtLeast(64)
        val out = ShortArray(count)
        var phase = 0.0
        for (i in 0 until count) {
            val t = i.toFloat() / count
            val freq = if (t < 0.42f) f1 else f2
            phase += 2.0 * PI * freq / sampleRate
            val env = (1f - t) * (1f - t * 0.5f)
            val s = (sin(phase) * 0.8 + sin(phase * 2.0) * 0.2).toFloat() * env * volume
            out[i] = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun synthArpeggio(
        sampleRate: Int,
        notes: FloatArray,
        stepSec: Float,
        volume: Float
    ): ShortArray {
        val stepSamples = (sampleRate * stepSec).toInt().coerceAtLeast(64)
        val total = stepSamples * notes.size
        val out = ShortArray(total)
        var phase = 0.0
        for (n in notes.indices) {
            val freq = notes[n]
            for (i in 0 until stepSamples) {
                val localT = i.toFloat() / stepSamples
                phase += 2.0 * PI * freq / sampleRate
                val env = (1f - localT * 0.65f)
                val s = (sin(phase) * 0.78 + sin(phase * 1.5) * 0.22).toFloat() * env * volume
                out[n * stepSamples + i] = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    fun release() {
        scope.cancel()
    }
}
