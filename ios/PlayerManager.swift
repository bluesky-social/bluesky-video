//
//  PlayerManager.swift
//  BlueskyVideo
//
//  Created by Hailey on 9/10/24.
//

import AVFoundation

class PlayerManager {
  static let shared = PlayerManager()

  private var availalbePlayers: [AVPlayer] = []
  private var usedPlayers: Set<AVPlayer> = []

  func dequeuePlayer() -> AVPlayer {
    if let player = availalbePlayers.popLast() {
      self.usedPlayers.insert(player)
      return player
    } else {
      let newPlayer = AVPlayer()
      self.applyDefaultsToPlayer(newPlayer)
      self.usedPlayers.insert(newPlayer)
      return newPlayer
    }
  }

  func recyclePlayer(_ player: AVPlayer) {
    self.resetPlayer(player)
    self.usedPlayers.remove(player)
    self.availalbePlayers.append(player)
  }

  private func resetPlayer(_ player: AVPlayer) {
    player.replaceCurrentItem(with: nil)
    player.isMuted = true
    player.pause()
    player.seek(to: CMTime.zero)
  }

  // MARK: - configuration

  private func applyDefaultsToPlayer(_ player: AVPlayer) {
    player.automaticallyWaitsToMinimizeStalling = false
    player.preventsDisplaySleepDuringVideoPlayback = true
    player.isMuted = true

  }

  func allPlayers() -> [AVPlayer] {
    return Array(self.usedPlayers) + self.availalbePlayers
  }
}

extension AVPlayer {
  var isPlaying: Bool {
    return self.rate != 0 && self.error == nil
  }
}
