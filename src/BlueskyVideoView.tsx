import {requireNativeViewManager} from 'expo-modules-core'
import * as React from 'react'
import {StyleProp, ViewStyle} from 'react-native'

import {BlueskyVideoViewProps} from './BlueskyVideo.types'

const NativeView: React.ComponentType<
  BlueskyVideoViewProps & {
    style: StyleProp<ViewStyle>
    ref: React.Ref<any>
  }
> = requireNativeViewManager('BlueskyVideo')

export class BlueskyVideoView extends React.Component<
  BlueskyVideoViewProps & {style?: StyleProp<ViewStyle>}
> {
  ref: React.RefObject<any> = React.createRef()

  togglePlayback = () => {
    this.ref.current?.togglePlayback()
  }

  toggleMuted = () => {
    this.ref.current?.toggleMuted()
  }

  enterFullscreen = () => {
    this.ref.current?.enterFullscreen()
  }

  render() {
    return (
      <NativeView
        {...this.props}
        style={[{flex: 1}, this.props.style]}
        ref={this.ref}
      />
    )
  }
}
