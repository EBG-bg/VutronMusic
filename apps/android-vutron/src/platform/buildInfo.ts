import { version } from '../../package.json'

export interface MobileBuildInfo {
  edition: 'mobile' | 'personal'
  displayName: string
  version: string
  versionCode: number
  comparisonVersion: string
  artifactName: string
}

declare const __VUTRON_BUILD__: MobileBuildInfo | undefined
export const buildInfo: MobileBuildInfo = typeof __VUTRON_BUILD__ !== 'undefined' ? __VUTRON_BUILD__ : {
  edition: 'mobile', displayName: 'Vutron Music - Mobile', version,
  versionCode: 0, comparisonVersion: version, artifactName: ''
}
