package expo.modules.blueskyvideo

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

@UnstableApi
class PlayerManager(val context: Context) {
  companion object {
    private val availablePlayers = arrayOf<ExoPlayer>()
    private val usedPlayers = setOf<ExoPlayer>()
  }

  fun dequeuePlayer(): ExoPlayer {
    val player = availablePlayers.lastOrNull()
    if (player != null) {
      availablePlayers.dropLast(1)
      usedPlayers.plus(player)
      return player
    } else {
      val newPlayer = createExoPlayer()
      usedPlayers.plus(newPlayer)
      return newPlayer
    }
  }

  fun recyclePlayer(player: ExoPlayer) {
    if (usedPlayers.contains(player)) {
      this.resetPlayer(player)
      usedPlayers.minus(player)
      availablePlayers.plus(player)
    }
  }

  fun allPlayers(): List<ExoPlayer> {
    return availablePlayers.toList() + usedPlayers.toList()
  }

  private fun resetPlayer(player: ExoPlayer) {
    player.stop()
    player.seekTo(0)
  }

  private fun createExoPlayer(): ExoPlayer {
    return ExoPlayer.Builder(context)
      .apply {
        setLooper(context.mainLooper)
        setSeekForwardIncrementMs(5000)
        setSeekBackIncrementMs(5000)
      }
      .build()
  }
}