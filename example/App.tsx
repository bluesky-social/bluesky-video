import {BlueskyVideoView} from 'bluesky-video'
import {updateActiveVideoViewAsync} from 'bluesky-video/BlueskyVideoModule'
import React from 'react'
import {
  FlatList,
  ListRenderItemInfo,
  Platform,
  Pressable,
  SafeAreaView,
  View,
  Text,
  Switch,
  SwitchChangeEvent
} from 'react-native'

import {SAMPLE_VIDEOS} from './sampleVideos'

export default function App() {
  const data = React.useMemo(() => {
    return [...SAMPLE_VIDEOS, ...SAMPLE_VIDEOS]
  }, [])

  const [fullscreenKeepDisplayOn, setFullscreenKeepDisplayOn] =
    React.useState<boolean>(false)
  const toggleFullscreenKeepDisplayOn = React.useCallback(
    (event: SwitchChangeEvent) => {
      setFullscreenKeepDisplayOn(v => !v)
    },
    [setFullscreenKeepDisplayOn]
  )

  const renderItem = React.useCallback(
    ({item, index}: ListRenderItemInfo<string>) => {
      return (
        <Player
          url={item}
          num={index + 1}
          fullscreenKeepDisplayOn={fullscreenKeepDisplayOn}
        />
      )
    },
    [fullscreenKeepDisplayOn]
  )

  // @ts-ignore
  const uiManager = global?.nativeFabricUIManager ? 'Fabric' : 'Paper'

  return (
    <SafeAreaView style={{flex: 1}}>
      <Text style={{fontWeight: 'bold'}}>Options</Text>
      <View style={{flexDirection: 'row'}}>
        <Text>Keep display on when fullscreen</Text>
        <Switch
          onChange={toggleFullscreenKeepDisplayOn}
          value={fullscreenKeepDisplayOn}
        />
      </View>
      <Text style={{height: 20}}>Renderer: {uiManager}</Text>
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

function Player({
  url,
  num,
  fullscreenKeepDisplayOn
}: {
  url: string
  num: number
  fullscreenKeepDisplayOn: boolean
}) {
  const ref = React.useRef<BlueskyVideoView>(null)

  const onPress = () => {
    console.log('press')
    ref.current?.enterFullscreen(fullscreenKeepDisplayOn)
  }

  return (
    <Pressable
      style={{backgroundColor: 'blue', height: 300}}
      onPress={Platform.OS === 'ios' ? onPress : undefined}>
      <Text>Video: {num}</Text>
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
