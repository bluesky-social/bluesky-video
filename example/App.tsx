import {BlueskyVideoView} from 'bluesky-video'
import {updateActiveVideoViewAsync} from 'bluesky-video/BlueskyVideoModule'
import React from 'react'
import {
  FlatList,
  ListRenderItemInfo,
  Platform,
  Pressable,
  SafeAreaView,
  View
} from 'react-native'

import {SAMPLE_VIDEOS} from './sampleVideos'

export default function App() {
  const data = React.useMemo(() => {
    return [...SAMPLE_VIDEOS, ...SAMPLE_VIDEOS]
  }, [])

  const renderItem = React.useCallback(({item}: ListRenderItemInfo<string>) => {
    return <Player url={item} />
  }, [])

  return (
    <SafeAreaView style={{flex: 1}}>
      <View style={{flex: 1}}>
        <FlatList
          data={data}
          renderItem={renderItem}
          removeClippedSubviews
          onScroll={() => {
            updateActiveVideoViewAsync()
          }}
          scrollEventThrottle={250}
        />
      </View>
    </SafeAreaView>
  )
}

function Player({url}: {url: string}) {
  const ref = React.useRef<BlueskyVideoView>(null)

  const onPress = () => {
    console.log('press')
    ref.current?.togglePlayback()
  }

  return (
    <Pressable
      style={{backgroundColor: 'blue', height: 300}}
      onPress={Platform.OS === 'ios' ? onPress : undefined}>
      <BlueskyVideoView
        url={url}
        autoplay
        ref={ref}
        onError={e => {
          console.log('error', e.nativeEvent.error)
        }}
        onStatusChange={e => {
          console.log('status', e.nativeEvent.status)
        }}
        onLoadingChange={e => {
          console.log('loading', e.nativeEvent.isLoading)
        }}
        onTimeRemainingChange={e => {
          console.log('timeRemaining', e.nativeEvent.timeRemaining)
        }}
        onPlayerPress={Platform.OS === 'android' ? onPress : undefined}
      />
    </Pressable>
  )
}
