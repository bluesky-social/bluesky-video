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
  onStatusChange?: (
    e: NativeSyntheticEvent<{status: 'playing' | 'paused'}>
  ) => void
  onTimeRemainingChange?: (
    e: NativeSyntheticEvent<{timeRemaining: number}>
  ) => void
  onFullscreenChange?: (
    e: NativeSyntheticEvent<{isFullscreen: boolean}>
  ) => void
  onError?: (e: NativeSyntheticEvent<BlueskyVideoError>) => void
  style?: StyleProp<ViewStyle>
}
