//
//  Player.swift
//  BlueskyVideo
//
//  Created by Hailey on 9/11/24.
//

import AVFoundation

class PlayerItem: AVPlayerItem {
  weak var associatedPlayer: AVPlayer?
  weak var associatedView: VideoView?
}
