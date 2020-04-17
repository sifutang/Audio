package com.demo.audio

import android.media.*
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.Button
import android.widget.Toast
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), View.OnClickListener, SurfaceHolder.Callback {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var executorService: ExecutorService? = null

    private lateinit var surfaceView:AutoFitSurfaceView
    private lateinit var timeBtn: Button

    private var surfaceWidth = -1
    private var surfaceHeight = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.fetch_audio_btn).setOnClickListener(this)
        findViewById<Button>(R.id.codec_audio_btn).setOnClickListener(this)
        findViewById<Button>(R.id.codec_video_btn).setOnClickListener(this)
        surfaceView = findViewById(R.id.surface_view)
        timeBtn = findViewById(R.id.time_btn)

        surfaceView.holder.addCallback(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        executorService?.shutdown()
    }

    override fun onClick(v: View?) {
        if (executorService == null) {
            executorService = Executors.newFixedThreadPool(2)
        }
        when(v?.id) {
            R.id.fetch_audio_btn -> {
                executorService?.execute {
                    fetchAudio()
                }
            }
            R.id.codec_audio_btn -> {
                executorService?.execute {
                    codecAudioToPCM()
                }
            }
            R.id.codec_video_btn -> {
                executorService?.execute {
                    codecVideoToSurface()
                }
            }
        }
    }

    private fun codecVideoToSurface() {
        val assetFileDescriptor = assets.openFd("demo.mp4")
        val mediaExtractor = MediaExtractor()
        mediaExtractor.setDataSource(assetFileDescriptor)
        var videoTrackIndex = -1
        for (track in 0 until mediaExtractor.trackCount) {
            val format = mediaExtractor.getTrackFormat(track)
            Log.d(TAG, "codecVideoToSurface: format = $format")
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = track
                break
            }
        }

        if (videoTrackIndex != -1) {
            mediaExtractor.selectTrack(videoTrackIndex)
            val trackFormat = mediaExtractor.getTrackFormat(videoTrackIndex)
            val type = trackFormat.getString(MediaFormat.KEY_MIME)!!
            val videoWidth = trackFormat.getInteger(MediaFormat.KEY_WIDTH)
            val videoHeight = trackFormat.getInteger(MediaFormat.KEY_HEIGHT)
            Log.d(TAG, "codecVideoToSurface: type = $type, videoWidth = $videoWidth, videoHeight = $videoHeight")
            runOnUiThread {
                if (videoWidth > videoHeight) {
                    // base w
                    val ratio = 1f * videoWidth / surfaceWidth
                    if (ratio > 1) {
                        val viewHeight = videoHeight / ratio
                        surfaceView.setAspectRatio(surfaceWidth, viewHeight.toInt())
                    }
                }
            }
            val videoCodec = MediaCodec.createDecoderByType(type)
            val surface = surfaceView.holder.surface
            videoCodec.configure(trackFormat, surface, null, 0)
            videoCodec.start()

            val decodeBufferInfo = MediaCodec.BufferInfo()
            var readFinished = false
            var decodeFinished = false
            val startMs = System.currentTimeMillis()
            while (!readFinished && !decodeFinished) {
                // send data to codec
                if (!readFinished) {
                    val inputIndex = videoCodec.dequeueInputBuffer(10000)
                    if (inputIndex < 0) {
                        Log.e(TAG, "codecVideoToSurface: may be input stream not available")
                        break
                    }

                    val inputBuffer = videoCodec.getInputBuffer(inputIndex) ?: continue
                    inputBuffer.clear()
                    val sampleSize = mediaExtractor.readSampleData(inputBuffer, 0)
                    Log.d(TAG, "codecVideoToSurface: inputIndex = $inputIndex, sampleSize = $sampleSize")
                    if (sampleSize < 0) {
                        videoCodec.queueInputBuffer(inputIndex,
                            0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        readFinished = true
                        Log.d(TAG, "codecVideoToSurface: ----------------------read finished")
                    } else {
                        val sampleTime = mediaExtractor.sampleTime
                        videoCodec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                        mediaExtractor.advance()
                        runOnUiThread {
                            timeBtn.text = calculateTime((sampleTime / 1000).toInt())
                        }
                    }
                }

                // render decode data
                while (true) {
                    val outputIndex = videoCodec.dequeueOutputBuffer(decodeBufferInfo, 10000)
                    Log.d(TAG, "codecVideoToSurface: outputIndex = $outputIndex")
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.e(TAG, "codecVideoToSurface: not available output")
                        break
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = videoCodec.outputFormat
                        Log.d(TAG, "codecVideoToSurface: newFormat = $newFormat")
                    } else if (outputIndex < 0) {
                        // do nothing
                    } else {
                        val outputBuffer = videoCodec.getOutputBuffer(outputIndex) ?: continue
                        outputBuffer.clear()
                        sync(decodeBufferInfo, startMs)
                        videoCodec.releaseOutputBuffer(outputIndex, true)
                        if ((decodeBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "codecVideoToSurface: -------------------finish")
                            decodeFinished = true
                            break
                        }
                    }
                }
            }

            videoCodec.stop()
            videoCodec.release()
        } else {
            Log.e(TAG, "codecVideoToSurface: not match video track")
        }

        mediaExtractor.release()
    }

    private fun sync(info: MediaCodec.BufferInfo, startMs: Long) {
        val timeDiff = info.presentationTimeUs / 1000 - (System.currentTimeMillis() - startMs)
        Log.d(TAG, "sync: time diff = $timeDiff")
        if (timeDiff > 0) {
            try {
                Thread.sleep(timeDiff)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    private fun codecAudioToPCM() {
        val assetFileDescriptor = assets.openFd("demo.mp4")
        val mediaExtractor = MediaExtractor()
        var audioTrackIndex = -1
        mediaExtractor.setDataSource(assetFileDescriptor)
        for (track in 0 until mediaExtractor.trackCount) {
            val format = mediaExtractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = track
                break
            }
        }

        if (audioTrackIndex != -1) {
            mediaExtractor.selectTrack(audioTrackIndex)

            val trackFormat = mediaExtractor.getTrackFormat(audioTrackIndex)
            // init audio decoder
            val audioCodec = MediaCodec.createDecoderByType(trackFormat.getString(MediaFormat.KEY_MIME)!!)
            audioCodec.configure(trackFormat, null, null, 0)
            audioCodec.start()

            val decodeBufferInfo = MediaCodec.BufferInfo()
            var audioTrack: AudioTrack? = null
            var readFinished = false
            var decodeFinished = false
            while (!readFinished && !decodeFinished) {
                // send data to codec
                if (!readFinished) {
                    val inputBufferIndex = audioCodec.dequeueInputBuffer(0)
                    if (inputBufferIndex < 0) {
                        Log.e(TAG, "codecAudioToPCM: may be input stream not available")
                        break
                    }

                    // get available buffer
                    val inputBuffer = audioCodec.getInputBuffer(inputBufferIndex) ?: continue
                    // clear old buffer
                    inputBuffer.clear()
                    // read audio data to buffer
                    val sampleSize = mediaExtractor.readSampleData(inputBuffer, 0)
                    Log.d(TAG, "codecAudioToPCM: sample size = $sampleSize, inputBufferIndex = $inputBufferIndex")
                    if (sampleSize < 0) {
                        audioCodec.queueInputBuffer(inputBufferIndex,
                            0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        readFinished = true
                        Log.i(TAG, "codecAudioToPCM: ------------------read finish")
                    } else {
                        val sampleTime = mediaExtractor.sampleTime
                        audioCodec.queueInputBuffer(inputBufferIndex,
                            0, sampleSize, sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        // move to next frame
                        mediaExtractor.advance()
                        runOnUiThread {
                            timeBtn.text = calculateTime((sampleTime / 1000).toInt())
                        }
                    }
                }

                // read decode data
                while (true) {
                    val outputIndex = audioCodec.dequeueOutputBuffer(decodeBufferInfo, 1000)
                    Log.d(TAG, "codecAudioToPCM: outputIndex = $outputIndex")
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.e(TAG, "codecAudioToPCM: not available output")
                        break
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = audioCodec.outputFormat
                        audioTrack = createAudioTrack(newFormat)
                        audioTrack.play()
                        Log.i(TAG, "codecAudioToPCM: newFormat = $newFormat, audioTrack state = ${audioTrack.state}")
                    } else if (outputIndex < 0) {
                        // do nothing
                    } else {
                        val outputBuffer = audioCodec.getOutputBuffer(outputIndex) ?: continue
                        val pcmChunk = ByteArray(decodeBufferInfo.size)
                        outputBuffer.get(pcmChunk)
                        outputBuffer.clear()
                        audioCodec.releaseOutputBuffer(outputIndex, false)
                        audioTrack?.write(pcmChunk, 0, pcmChunk.size)

                        if ((decodeBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "codecAudioToPCM: -------------------finish")
                            decodeFinished = true
                            break
                        }
                        Log.d(TAG, "codecAudioToPCM: pcmChunk.size = ${pcmChunk.size}")
                    }
                }
            }

            audioCodec.stop()
            audioCodec.release()
            audioTrack?.stop()
            audioTrack?.release()
        } else {
            Log.e(TAG, "codecAudioToPCM: not match audio track")
        }

        mediaExtractor.release()
    }

    private fun createAudioTrack(format: MediaFormat): AudioTrack {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var channelMask = AudioFormat.CHANNEL_OUT_MONO
        if (channelCount == 2) {
            channelMask = AudioFormat.CHANNEL_OUT_STEREO
        }
        val audioAttributes = AudioAttributes
            .Builder()
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build()

        val audioFormat = AudioFormat
            .Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(
            audioFormat.sampleRate, audioFormat.channelMask, audioFormat.encoding)

        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBufferSize)
            .build()
    }

    private fun calculateTime(timeMs: Int): String {
        val time = timeMs / 1000
        val hour = time / 3600
        val minute = time / 60
        val second = time - hour * 3600 - minute * 60
        return "${alignment(hour)}:${alignment(minute)}:${alignment(second)}"
    }

    private fun alignment(time: Int): String {
        return if (time > 9) "$time" else "0$time"
    }

    private fun fetchAudio() {
        val assetFileDescriptor = assets.openFd("demo.mp4")
        val mediaExtractor = MediaExtractor()
        var audioTrack = -1
        mediaExtractor.setDataSource(assetFileDescriptor)
        for (track in 0 until mediaExtractor.trackCount) {
            val format = mediaExtractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            Log.e(TAG, "track = $track, format = $format, mime = $mime")
            if (mime.startsWith("audio/")) {
                audioTrack = track
                break
            }
        }

        if (audioTrack != -1) {
            val savePath = "${filesDir.absoluteFile}/test.mp4"
            Log.d(TAG, "audio save path = $savePath")

            mediaExtractor.selectTrack(audioTrack)
            val mediaMuxer = MediaMuxer(savePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackFormat = mediaExtractor.getTrackFormat(audioTrack)
            val writeAudioIndex = mediaMuxer.addTrack(trackFormat)
            mediaMuxer.start()

            val byteBuffer = ByteBuffer.allocate(trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            val bufferInfo = MediaCodec.BufferInfo()
            mediaExtractor.readSampleData(byteBuffer, 0)
            if (mediaExtractor.sampleFlags == MediaExtractor.SAMPLE_FLAG_SYNC) {
                mediaExtractor.advance()
            }

            while (true) {
                val readSampleSize = mediaExtractor.readSampleData(byteBuffer, 0)
                Log.d(TAG, "read audio data, cur size = $readSampleSize")
                if (readSampleSize < 0) {
                    break
                }

                bufferInfo.size = readSampleSize
                bufferInfo.flags = mediaExtractor.sampleFlags
                bufferInfo.offset = 0
                bufferInfo.presentationTimeUs = mediaExtractor.sampleTime
                Log.d(TAG, "write audio data, bufferInfo.flags = ${bufferInfo.flags}")
                mediaMuxer.writeSampleData(writeAudioIndex, byteBuffer, bufferInfo)
                mediaExtractor.advance()
            }

            mediaMuxer.release()
            mediaExtractor.release()

            runOnUiThread {
                Toast.makeText(this, savePath, Toast.LENGTH_LONG).show()
            }
        } else {
            Log.e(TAG, "fetchAudio: not match audio track")
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged: w = $width, h = $height")
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        Log.d(TAG, "surfaceDestroyed: ")
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        Log.d(TAG, "surfaceCreated: ")
    }
}