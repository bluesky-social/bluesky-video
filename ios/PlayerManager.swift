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
  private var playerItems: [URL: PlayerItem] = [:]
  
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
  
  func playerItem(url: URL, player: AVPlayer) -> AVPlayerItem {
    var playerItem: PlayerItem
    
    if let item = playerItems[url] {
      // Always ensure that no other player is using this item
      if item.associatedPlayer != nil {
        item.associatedPlayer?.replaceCurrentItem(with: nil)
        item.associatedPlayer = nil
      }
      playerItem = item
    } else {
      let newItem = PlayerItem(url: url)
      self.applyDefaultsToPlayerItem(newItem)
      
      // Never store over 3 items at once
      if playerItems.count >= 4 {
        let oldestItem = playerItems.removeValue(forKey: playerItems.keys.first!)
        oldestItem?.associatedPlayer?.replaceCurrentItem(with: nil)
      }
      
      playerItems[url] = newItem
      playerItem = newItem
    }
    
    playerItem.associatedPlayer = player
    return playerItem
  }
  
  private func resetPlayer(_ player: AVPlayer) {
    player.replaceCurrentItem(with: nil)
    player.isMuted = true
    player.pause()
    player.seek(to: CMTime.zero)
  }
  
  func clearPlayerItems() {
    self.playerItems.removeAll()
  }
  
  // MARK: - configuration
  
  private func applyDefaultsToPlayer(_ player: AVPlayer) {
    player.automaticallyWaitsToMinimizeStalling = false
    player.preventsDisplaySleepDuringVideoPlayback = true
    player.isMuted = true
  
  }
  
  private func applyDefaultsToPlayerItem(_ playerItem: PlayerItem) {
    // Prefer a buffer size of 10 seconds
    playerItem.preferredForwardBufferDuration = 10
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
