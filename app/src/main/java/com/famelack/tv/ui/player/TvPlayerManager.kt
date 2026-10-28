package com.famelack.tv.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.datasource.DefaultHttpDataSource
import com.famelack.tv.data.model.Channel
import com.famelack.tv.util.MemoryMonitor

interface PlayerEventListener {
    fun onPlaybackStarted(channel: Channel, index: Int)
    fun onBuffering(isBuffering: Boolean)
    fun onStreamError(errorMsg: String, canRetry: Boolean, retryAttempt: Int)
    fun onStreamUnavailable(channel: Channel)
}

/**
 * Ultra-lean ExoPlayer manager optimized for 512MB RAM TV devices.
 * - Single reused player instance.
 * - Strict 2.5s-8.0s live buffer window and 2MB buffer cap.
 * - Capped at 720p 30fps.
 * - Max 2 auto-retries before signaling unavailable.
 */
class TvPlayerManager(
    private val context: Context,
    private val listener: PlayerEventListener
) {

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var channelList: List<Channel> = emptyList()
    private var currentIndex: Int = -1
    private var currentChannel: Channel? = null

    private var retryCount = 0
    private val maxRetries = 2

    fun setPlaylist(channels: List<Channel>) {
        this.channelList = channels
    }

    fun initPlayer(surfaceView: SurfaceView, maxResolution: String = "720p") {
        if (exoPlayer != null) return

        // 1. Configure bandwidth-aware track selector capped to 720p or 480p
        val (maxWidth, maxHeight) = when (maxResolution) {
            "480p" -> Pair(854, 480)
            "1080p" -> Pair(1920, 1080)
            else -> Pair(1280, 720) // default 720p
        }

        trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(maxWidth, maxHeight)
                    .setMaxVideoFrameRate(30)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setForceLowestBitrate(false)
            )
        }

        // 2. Configure ultra-small live buffer control (2.5s to 8s max, 2MB buffer ceiling)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2500,
                /* maxBufferMs = */ 8000,
                /* bufferForPlaybackMs = */ 1500,
                /* bufferForPlaybackAfterRebufferMs = */ 2500
            )
            .setTargetBufferBytes(2 * 1024 * 1024) // 2 MB buffer cap
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 3. Build player
        exoPlayer = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build().apply {
                setVideoSurfaceView(surfaceView)
                playWhenReady = true
                addListener(playerListener)
            }

        MemoryMonitor.logHeapStats("Player Initialized")
    }

    fun playChannelAtIndex(index: Int) {
        if (index < 0 || index >= channelList.size) return
        currentIndex = index
        playChannel(channelList[index])
    }

    fun playChannel(channel: Channel) {
        currentChannel = channel
        retryCount = 0
        loadCurrentStream()
    }

    private fun loadCurrentStream() {
        val player = exoPlayer ?: return
        val channel = currentChannel ?: return

        mainHandler.removeCallbacksAndMessages(null)
        listener.onBuffering(true)
        listener.onPlaybackStarted(channel, currentIndex)

        try {
            // Build custom HTTP data source with standard TV user-agent and generous timeout
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("FamelackTV/1.0 (Android TV; Linux)")

            val mediaSource = HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(channel.url)))

            player.stop()
            player.clearMediaItems()
            player.setMediaSource(mediaSource)
            player.prepare()
            player.play()

            Log.i(TAG, "Playing channel: ${channel.name} (${channel.url})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load channel stream", e)
            handleStreamError("Failed to open stream: ${e.message}")
        }
    }

    private fun handleStreamError(errorMsg: String) {
        val channel = currentChannel ?: return
        if (retryCount < maxRetries) {
            retryCount++
            Log.w(TAG, "Stream error on ${channel.name}. Auto-retry $retryCount/$maxRetries in 1.5s...")
            listener.onStreamError(errorMsg, canRetry = true, retryAttempt = retryCount)
            mainHandler.postDelayed({
                loadCurrentStream()
            }, 1500)
        } else {
            Log.e(TAG, "Stream permanently failed for ${channel.name} after $maxRetries retries.")
            listener.onStreamError(errorMsg, canRetry = false, retryAttempt = retryCount)
            listener.onStreamUnavailable(channel)
        }
    }

    fun nextChannel(): Channel? {
        if (channelList.isEmpty()) return null
        val nextIdx = if (currentIndex + 1 < channelList.size) currentIndex + 1 else 0
        playChannelAtIndex(nextIdx)
        return currentChannel
    }

    fun previousChannel(): Channel? {
        if (channelList.isEmpty()) return null
        val prevIdx = if (currentIndex - 1 >= 0) currentIndex - 1 else channelList.size - 1
        playChannelAtIndex(prevIdx)
        return currentChannel
    }

    fun getCurrentChannel(): Channel? = currentChannel
    fun getCurrentIndex(): Int = currentIndex

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> listener.onBuffering(true)
                Player.STATE_READY -> {
                    listener.onBuffering(false)
                    retryCount = 0
                    MemoryMonitor.logHeapStats("Player Ready")
                }
                Player.STATE_ENDED -> listener.onBuffering(false)
                Player.STATE_IDLE -> listener.onBuffering(false)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val message = error.localizedMessage ?: "Playback error (${error.errorCodeName})"
            Log.w(TAG, "ExoPlayer Error: $message (code: ${error.errorCode})")
            handleStreamError(message)
        }
    }

    /**
     * Complete player release on onStop/onPause to prevent Activity and decoder leaks.
     */
    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        exoPlayer?.let { player ->
            player.removeListener(playerListener)
            player.stop()
            player.clearMediaItems()
            player.release()
        }
        exoPlayer = null
        trackSelector = null
        currentChannel = null
        MemoryMonitor.logHeapStats("Player Released")
    }

    companion object {
        private const val TAG = "TvPlayerManager"
    }
}

