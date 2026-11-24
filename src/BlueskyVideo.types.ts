import {NativeSyntheticEvent, StyleProp, ViewStyle} from 'react-native'

export type BlueskyVideoError = {
  /** Generic message, the same on every platform */
  error: string
  errorDescription?: string
  /** iOS only: NSError domain */
  errorDomain?: string
  /** iOS: NSError code. Android: PlaybackException error code */
  errorCode?: number
  /** Android only: PlaybackException error code name, e.g. ERROR_CODE_IO_BAD_HTTP_STATUS */
  errorCodeName?: string
  /** Android only: the HTTP status, when the error is ERROR_CODE_IO_BAD_HTTP_STATUS */
  httpResponseCode?: number
  /** iOS only: the underlying error, which usually holds the real cause */
  underlyingErrorDomain?: string
  underlyingErrorCode?: number
}

export type BlueskyVideoViewProps = {
  url: string
  autoplay: boolean
  beginMuted?: boolean
  forceTakeover?: boolean
  accessibilityHint?: string
  accessibilityLabel?: string

  onActiveChange?: (e: NativeSyntheticEvent<{isActive: boolean}>) => void
  onLoadingChange?: (e: NativeSyntheticEvent<{isLoading: boolean}>) => void
  onMutedChange?: (e: NativeSyntheticEvent<{isMuted: boolean}>) => void
  onPlayerPress?: () => void
  /**
   * Follows the player, so it also reports pauses the system makes itself,
   * e.g. for an incoming call, another app taking audio focus, or headphones
   * being unplugged.
   */
  onStatusChange?: (
    e: NativeSyntheticEvent<{status: 'playing' | 'paused'}>
  ) => void
  /**
   * Playback stalled waiting for data after the video had loaded. Use
   * `onLoadingChange` for the initial load.
   */
  onBufferingChange?: (e: NativeSyntheticEvent<{isBuffering: boolean}>) => void
  onTimeRemainingChange?: (
    e: NativeSyntheticEvent<{
      timeRemaining: number
      /**
       * Total length of the video in seconds. Absent until the length is
       * known (e.g. before the video has loaded).
       */
      duration?: number
    }>
  ) => void
  onFullscreenChange?: (
    e: NativeSyntheticEvent<{isFullscreen: boolean}>
  ) => void
  onError?: (e: NativeSyntheticEvent<BlueskyVideoError>) => void
  style?: StyleProp<ViewStyle>
}
