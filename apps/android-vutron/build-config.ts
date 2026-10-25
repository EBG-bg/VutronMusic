import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import type { MobileBuildInfo } from './src/platform/buildInfo'

const repository = existsSync(resolve(process.cwd(), 'apps/android-vutron/package.json')) ? process.cwd() : resolve(process.cwd(), '../..')
const resolver = createRequire(resolve(repository, 'apps/android-vutron/package.json'))('../../scripts/mobile-build.cjs') as {
  resolveBuildInfo(repository: string): MobileBuildInfo
}
export const mobileBuild = resolver.resolveBuildInfo(repository)
