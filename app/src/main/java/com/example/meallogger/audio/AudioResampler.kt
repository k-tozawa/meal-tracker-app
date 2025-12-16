package com.example.meallogger.audio

import kotlin.math.roundToInt

/**
 * Audio resampler for downsampling PCM audio data
 * Uses simple linear interpolation
 */
class AudioResampler {

    /**
     * Downsample audio from 48kHz to 16kHz (3:1 ratio)
     * @param input 16-bit PCM audio data at 48kHz
     * @return 16-bit PCM audio data at 16kHz
     */
    fun downsample48to16(input: ByteArray): ByteArray {
        if (input.size % 2 != 0) {
            throw IllegalArgumentException("Input size must be even for 16-bit PCM")
        }

        val inputSamples = input.size / 2
        val outputSamples = inputSamples / 3  // 48kHz to 16kHz is 3:1 ratio
        val output = ByteArray(outputSamples * 2)

        for (i in 0 until outputSamples) {
            // Take every 3rd sample (simple decimation)
            val inputIdx = i * 3
            if (inputIdx < inputSamples) {
                val byteIdx = inputIdx * 2
                output[i * 2] = input[byteIdx]
                output[i * 2 + 1] = input[byteIdx + 1]
            }
        }

        return output
    }

    /**
     * Downsample audio with linear interpolation for better quality
     * @param input 16-bit PCM audio data at source rate
     * @param sourceRate Source sample rate in Hz
     * @param targetRate Target sample rate in Hz
     * @return 16-bit PCM audio data at target rate
     */
    fun downsample(input: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        if (input.size % 2 != 0) {
            throw IllegalArgumentException("Input size must be even for 16-bit PCM")
        }

        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val inputSamples = input.size / 2
        val outputSamples = (inputSamples / ratio).roundToInt()
        val output = ByteArray(outputSamples * 2)

        for (i in 0 until outputSamples) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()

            if (srcIdx < inputSamples - 1) {
                // Linear interpolation between two samples
                val fraction = srcPos - srcIdx

                // Convert bytes to 16-bit samples
                val sample1 = bytesToShort(input, srcIdx * 2)
                val sample2 = bytesToShort(input, (srcIdx + 1) * 2)

                // Interpolate
                val interpolated = (sample1 * (1 - fraction) + sample2 * fraction).roundToInt()
                val clipped = interpolated.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                // Convert back to bytes
                output[i * 2] = (clipped.toInt() and 0xFF).toByte()
                output[i * 2 + 1] = (clipped.toInt() shr 8).toByte()
            } else if (srcIdx < inputSamples) {
                // Last sample, no interpolation
                output[i * 2] = input[srcIdx * 2]
                output[i * 2 + 1] = input[srcIdx * 2 + 1]
            }
        }

        return output
    }

    private fun bytesToShort(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xFF)).toShort()
    }
}
