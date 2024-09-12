import {requireNativeModule} from 'expo-modules-core'

// It loads the native module object from the JSI or falls back to
// the bridge module (from NativeModulesProxy) if the remote debugger is on.
const NativeModule = requireNativeModule('BlueskyVideo')

export async function updateActiveVideoViewAsync() {
  NativeModule.updateActiveVideoViewAsync()
}
