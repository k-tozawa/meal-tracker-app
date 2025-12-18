package com.example.meallogger.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.*

/**
 * 空のオーディオデータを再生するプレーヤー
 * エコーキャンセル（AEC）を有効にするために使用される
 */
class EmptyAudioPlayer {

    companion object {
        private const val STATIC_SAMPLING_RATE = 16000
        private const val STATIC_FORMAT_CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        private const val STATIC_FORMAT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile
    var mIsRunning = false
    private var mAudioPlayer: AudioTrack? = null
    private val mLockObject = java.lang.Object()
    private var mPlayThread: Thread? = null

    @Synchronized
    fun start() {
        if (mIsRunning)
            return

        val bufferSize = android.media.AudioTrack.getMinBufferSize(
            STATIC_SAMPLING_RATE,
            STATIC_FORMAT_CHANNEL,
            STATIC_FORMAT_ENCODING
        )

        mAudioPlayer = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(STATIC_FORMAT_ENCODING)
                    .setSampleRate(STATIC_SAMPLING_RATE)
                    .setChannelMask(STATIC_FORMAT_CHANNEL)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        mAudioPlayer?.play()
        mIsRunning = true

        mPlayThread = Thread(mPlayerThreadRunnable)
        mPlayThread?.start()
        synchronized(mLockObject) {
            try {
                mLockObject.wait()
            } catch (aException: InterruptedException) {
                aException.printStackTrace()
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!mIsRunning)
            return

        mIsRunning = false
        try {
            mPlayThread?.join()
        } catch (aException: InterruptedException) {
            aException.printStackTrace()
        }

        mPlayThread = null
        mAudioPlayer?.stop()
        mAudioPlayer?.release()
        mAudioPlayer = null
    }

    private val mPlayerThreadRunnable = Runnable {
        val bufferSize = android.media.AudioTrack.getMinBufferSize(
            STATIC_SAMPLING_RATE,
            STATIC_FORMAT_CHANNEL,
            STATIC_FORMAT_ENCODING
        )

        val emptyData = ByteArray(bufferSize)
        Arrays.fill(emptyData, 0)
        val waitTime = ((bufferSize / 2) * 1000 / STATIC_SAMPLING_RATE) / 2
        var isFirstCall = true

        while (mIsRunning) {
            mAudioPlayer?.write(emptyData, 0, bufferSize)
            if (isFirstCall) {
                synchronized(mLockObject) {
                    mLockObject.notifyAll()
                }
                isFirstCall = false
            }

            try {
                Thread.sleep(waitTime.toLong())
            } catch (aException: InterruptedException) {
                aException.printStackTrace()
            }
        }
    }
}
