import {NativeSyntheticEvent} from 'react-native'

export type BlueskyVideoViewProps = {
  url: string
  autoplay: boolean
  onStatusChange?: (
    e: NativeSyntheticEvent<{status: 'playing' | 'paused'}>
  ) => void
  onLoadingChange?: (e: NativeSyntheticEvent<{isLoading: boolean}>) => void
  onError?: (e: NativeSyntheticEvent<{error: string}>) => void
  onMutedChange?: (e: NativeSyntheticEvent<{isMuted: boolean}>) => void
  onTimeRemainingChange?: (
    e: NativeSyntheticEvent<{timeRemaining: number}>
  ) => void
}
