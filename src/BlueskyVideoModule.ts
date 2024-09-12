import {requireNativeModule} from 'expo-modules-core'

const NativeModule = requireNativeModule('BlueskyVideo')

export async function updateActiveVideoViewAsync() {
  NativeModule.updateActiveVideoViewAsync()
}
