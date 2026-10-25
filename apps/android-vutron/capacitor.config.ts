import type { CapacitorConfig } from '@capacitor/cli'
import { mobileBuild } from './build-config'

const config: CapacitorConfig = {
  appId: 'io.github.vutronmusic.personal.webshell',
  appName: mobileBuild.displayName,
  webDir: 'dist',
  android: {
    backgroundColor: '#ffffff'
  }
}

export default config
