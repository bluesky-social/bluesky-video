//
//  AudioManagement.swift
//  BlueskyVideo
//
//  Created by Hailey on 9/10/24.
//

import AVFAudio

class AudioManagement {
  static let shared = AudioManagement()

  let audioSession = AVAudioSession.sharedInstance()
  var prevAudioCategory: AVAudioSession.Category?
  var prevAudioActive: Bool = false

  /// Every session call goes through this queue so that category and activation changes are applied
  /// in the order they were requested, and never off the main thread.
  private let queue = DispatchQueue(label: "app.bsky.video.audio-session", qos: .userInitiated)

  func setPlayingVideo(_ playing: Bool) {
    if playing {
      self.setAudioCategory(category: .playback)
      self.setAudioActive(true)
    } else {
      self.setAudioCategory(category: .ambient)
      self.setAudioActive(false)
    }
  }

  /// Muted playback (autoplay, GIFs) must never take audio focus. Other code (e.g. expo-video) can
  /// leave the shared session on the nonmixable `.playback` category, which would make even a muted
  /// AVPlayer interrupt other apps' audio as soon as it starts. Switch synchronously so the category
  /// is correct before the player begins playing.
  func ensureMixing() {
    self.queue.sync {
      if self.audioSession.category == .ambient {
        return
      }
      try? self.audioSession.setCategory(.ambient)
    }
  }

  private func setAudioCategory(category: AVAudioSession.Category) {
    self.queue.async {
      if self.audioSession.category == category {
        return
      }
      self.prevAudioCategory = self.audioSession.category
      try? self.audioSession.setCategory(category)
    }
  }

  private func setAudioActive(_ active: Bool) {
    self.queue.async {
      if active == self.prevAudioActive {
        return
      }

      do {
        if active {
          try self.audioSession.setActive(true)
        } else {
          try self.audioSession.setActive(false, options: [.notifyOthersOnDeactivation])
        }
        self.prevAudioActive = active
      } catch { }
    }
  }
}
