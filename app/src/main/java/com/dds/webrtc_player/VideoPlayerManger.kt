package com.dds.webrtc_player

import android.content.Context
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ui.PlayerView

class VideoPlayerManager(private val context: Context, private val playerView: PlayerView) {
    private val player: SimpleExoPlayer = SimpleExoPlayer.Builder(context).build()

    init {
        playerView.player = player
    }

    fun playStream(streamUrl: String) {
        val mediaItem = MediaItem.fromUri(streamUrl)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun stop() {
        player.stop()
    }
}
