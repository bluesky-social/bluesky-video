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

  func setPlayingVideo(_ playing: Bool) {
    if playing {
      self.setAudioCategory(category: .playback)
      self.setAudioActive(true)
    } else {
      self.setAudioCategory(category: .ambient)
      self.setAudioActive(false)
    }
  }

  private func setAudioCategory(category: AVAudioSession.Category) {
    if self.audioSession.category == category {
      return
    }

    self.prevAudioCategory = self.audioSession.category
    DispatchQueue.global(qos: .background).async {
      try? AVAudioSession.sharedInstance().setCategory(category)
    }
  }

  private func setAudioActive(_ active: Bool) {
    if active == self.prevAudioActive {
      return
    }

    if active {
      DispatchQueue.global(qos: .background).async {
        do {
          try AVAudioSession.sharedInstance().setActive(true)
          self.prevAudioActive = !self.prevAudioActive
        } catch { }
      }
    } else {
      DispatchQueue.global(qos: .background).async {
        do {
          try AVAudioSession.sharedInstance().setActive(false,
                                                         options: [.notifyOthersOnDeactivation])
          self.prevAudioActive = !self.prevAudioActive
        } catch { }
      }
    }
  }
}
