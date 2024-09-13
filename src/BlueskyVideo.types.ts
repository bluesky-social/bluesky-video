import {NativeSyntheticEvent, StyleProp, ViewStyle} from 'react-native'

export type BlueskyVideoViewProps = {
  url: string
  autoplay: boolean
  beginMuted: boolean
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
  onError?: (e: NativeSyntheticEvent<{error: string}>) => void
  style?: StyleProp<ViewStyle>
}
