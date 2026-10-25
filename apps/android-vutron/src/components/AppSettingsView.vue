<script setup lang="ts">
import { computed } from 'vue'
import { useAndroidShellStore } from '../stores/androidShell'
import { formatBytes } from '../utils/formatting'
import { buildInfo } from '../platform/buildInfo'
import OriginalIcon from './OriginalIcon.vue'
import AppearanceSettings from './AppearanceSettings.vue'
import AppUpdateSettings from './AppUpdateSettings.vue'
import LanguageSettings from './LanguageSettings.vue'
import LocalDirectoryManagement from './LocalDirectoryManagement.vue'
import UserBackupSettings from './UserBackupSettings.vue'
import CollapsibleSettingsCard from './CollapsibleSettingsCard.vue'
import LyricColorPicker from './LyricColorPicker.vue'
import LyricOffsetControl from './LyricOffsetControl.vue'

const store = useAndroidShellStore()
const emit = defineEmits<{ requestLogin: []; localMode: []; openOffline: [] }>()

const cacheUsedPercent = computed(() => store.cacheStatus.capacityMb > 0
  ? Math.min(100, (store.cacheStatus.usedBytes / (store.cacheStatus.capacityMb * 1024 * 1024)) * 100)
  : 0)

function updateCacheCapacity(event: Event) {
  store.cacheCapacityMb = Number((event.target as HTMLInputElement).value)
}

function updateArtworkCacheCapacity(event: Event) {
  store.artworkCacheCapacityMb = Number((event.target as HTMLInputElement).value)
}

function setSleepTimerFromInput() {
  void store.setSleepTimer(Number(store.sleepTimerMinutes))
}
</script>

<template>
<section class="page settings-page">
        <div class="section-heading"><div><div class="eyebrow">PREFERENCES</div><h2>{{ $t('nav.settings') }}</h2><p class="setting-note">{{ buildInfo.displayName }} · {{ buildInfo.version }}</p></div></div>
        <AppearanceSettings />
        <LanguageSettings />
        <section v-if="store.onlineAvailable" class="settings-card account-settings">
          <div class="settings-avatar">{{ store.profile?.nickname?.slice(0, 1) || 'V' }}</div>
          <div class="settings-account-copy"><b>{{ store.profile?.nickname }}</b><small>网易云音乐账号</small></div>
          <button type="button" class="text-button" @click="store.logout">退出</button>
        </section>
        <section v-else class="settings-card account-settings">
          <div class="settings-avatar"><OriginalIcon name="local" /></div>
          <div class="settings-account-copy"><b>{{ $t('mobile.login.localEntry') }}</b><small>本地音乐与已有缓存无需登录</small></div>
          <button type="button" class="text-button" @click="emit('requestLogin')">登录网易云</button>
        </section>
        <button v-if="store.onlineAvailable" type="button" class="wide-outline-button" @click="emit('localMode')">切换为仅本地模式</button>
        <CollapsibleSettingsCard section-id="sources" title="音源与音质">
          <template #heading><span class="settings-icon violet"><OriginalIcon name="explore" /></span><div><h3>音源与音质</h3><p>自动匹配其他音源，并优先选择最高音质</p></div></template>
          <label class="settings-detail"><span>网易云不可用时自动寻找其它音源</span><input type="checkbox" :checked="store.autoMatchSources" @change="store.setAutoMatchSources(($event.target as HTMLInputElement).checked)" /></label>
          <p class="setting-note">优先播放完整网易云音频；其它来源核对歌名、全部歌手、版本和专辑或时长，默认选择通过核对的最高音质。</p>
        </CollapsibleSettingsCard>
        <CollapsibleSettingsCard section-id="local" :title="$t('nav.localMusic')">
          <template #heading><span class="settings-icon violet"><OriginalIcon name="local" /></span><div><h3>{{ $t('nav.localMusic') }}</h3><p>自动识别选定文件夹中的歌曲</p></div></template>
          <div class="settings-detail"><span>{{ store.folderName }}</span><button class="secondary-button" type="button" @click="store.pickMusicFolder">{{ $t('mobile.library.chooseFolder') }}</button></div>
          <button class="wide-outline-button" type="button" :disabled="store.localScanLoading" @click="store.loadLocalMusic(true)">重新扫描 · {{ store.localTracks.length }} 首</button>
        </CollapsibleSettingsCard>
        <LocalDirectoryManagement />
        <CollapsibleSettingsCard section-id="cache" :title="$t('mobile.library.cache')">
          <template #heading><span class="settings-icon blue"><OriginalIcon name="down" /></span><div><h3>{{ $t('mobile.library.cache') }}</h3><p>歌曲 {{ formatBytes(store.cacheStatus.usedBytes) }} / {{ store.cacheStatus.capacityMb }} MB · {{ store.cacheStatus.trackCount }} 首</p><p>歌单与封面 {{ formatBytes(store.cacheStatus.libraryBytes ?? 0) }}</p></div></template>
          <label class="settings-detail"><span>自动保存已播放歌曲</span><input type="checkbox" :checked="store.cacheStatus.enabled !== false" @change="store.setAutomaticCache(($event.target as HTMLInputElement).checked)" /></label>
          <p class="setting-note">打开或播放歌单时会静默保存曲目资料和封面；音频随播放顺序自动缓存，无需逐个点击。</p>
          <CollapsibleSettingsCard class="cache-advanced" section-id="cache-prefetch" title="提前缓存其它歌单" :remember="false"><button class="secondary-button" type="button" :disabled="store.cacheAllPlaylistsLoading" @click="store.cacheAllPlaylists()">{{ store.cacheAllPlaylistsLoading ? '正在保存资料…' : '提前保存全部歌单资料与封面' }}</button><p class="setting-note">{{ store.cacheAllPlaylistsResult }}</p></CollapsibleSettingsCard>
          <p v-if="store.cacheStatus.downloading || store.cacheStatus.queued" class="setting-note">{{ store.cacheStatus.downloading ? `正在缓存 ${formatBytes(store.cacheStatus.downloadedBytes ?? 0)} / ${formatBytes(store.cacheStatus.downloadTotalBytes ?? 0)}` : '' }} · 等待 {{ store.cacheStatus.queued ?? 0 }} 首</p>
          <p v-if="store.cacheStatus.error" class="inline-error">{{ store.cacheStatus.error }}</p>
          <div class="storage-meter"><span :style="{ width: `${cacheUsedPercent}%` }"></span></div>
          <label class="setting-range"><span>容量上限</span><b>{{ store.cacheCapacityMb }} MB</b></label>
          <div class="cache-input-row"><input v-model.number="store.cacheCapacityMb" type="number" min="256" max="10240" inputmode="numeric" aria-label="音频缓存容量 MiB" /><button class="secondary-button" type="button" @click="store.setCacheCapacity()">保存上限</button></div>
          <input class="settings-slider" type="range" min="256" max="10240" step="256" :value="store.cacheCapacityMb" @input="updateCacheCapacity" @change="store.setCacheCapacity" />
          <div class="settings-detail"><span>播放时保存歌曲，供离线收听</span><button class="text-button danger-text" type="button" @click="store.clearCache">清空</button></div>
          <button class="wide-outline-button" type="button" @click="emit('openOffline')">查看离线歌曲</button>
          <label class="setting-range"><span>封面缓存上限</span><b>{{ store.artworkCacheCapacityMb }} MB</b></label>
          <input class="settings-slider" type="range" min="32" max="1024" step="32" :value="store.artworkCacheCapacityMb" @input="updateArtworkCacheCapacity" @change="store.setArtworkCacheCapacity" />
        </CollapsibleSettingsCard>
        <CollapsibleSettingsCard section-id="timer" title="睡眠定时">
          <template #heading><span class="settings-icon coral"><OriginalIcon name="timer" /></span><div><h3>睡眠定时</h3><p>{{ store.sleepTimerRemainingMs > 0 ? `将在 ${store.sleepTimerLabel} 后暂停` : '设定时间后自动暂停播放' }}</p></div></template>
          <div class="settings-detail"><label for="sleep-timer-minutes">{{ $t('settings.lyric.minute') }}</label><input id="sleep-timer-minutes" v-model.number="store.sleepTimerMinutes" type="number" min="1" max="1440" inputmode="numeric" /></div>
          <div class="timer-actions"><button class="secondary-button" type="button" @click="setSleepTimerFromInput">开始定时</button><button class="text-button" type="button" :disabled="!store.sleepTimerRemainingMs" @click="store.clearSleepTimer">取消定时</button></div>
        </CollapsibleSettingsCard>
        <CollapsibleSettingsCard section-id="lyrics" :title="$t('settings.nav.osdLyric')">
          <template #heading><span class="settings-icon coral"><OriginalIcon name="lyrics" /></span><div><h3>{{ $t('settings.nav.osdLyric') }}</h3><p>悬浮歌词支持逐字滚动和翻译同步</p></div></template>
          <div class="settings-detail"><span>显示悬浮歌词</span><button class="switch" :class="{ on: store.overlayEnabled }" type="button" role="switch" :aria-checked="store.overlayEnabled" @click="store.toggleLyricOverlay()"><i></i></button></div>
          <p class="setting-note">首次启用需要在系统设置中授予悬浮窗权限。</p>
          <div class="timer-actions"><button class="secondary-button" type="button" :disabled="!store.overlayEnabled" @click="store.temporarilyHideOverlay()">临时隐藏 30 秒</button><button class="secondary-button" type="button" :disabled="!store.overlayEnabled" @click="store.restoreOverlay()">立即恢复</button></div>
          <label class="settings-detail"><span>Wi-Fi 下自动校验歌词缓存</span><input type="checkbox" :checked="store.overlaySettings.wifiValidation" @change="store.updateOverlayField('wifiValidation', ($event.target as HTMLInputElement).checked)" /></label>
          <label class="settings-detail"><span>无原始逐字时估算逐字歌词</span><input type="checkbox" :checked="store.overlaySettings.estimatedWordTiming" @change="store.updateOverlayField('estimatedWordTiming', ($event.target as HTMLInputElement).checked)" /></label>
          <p class="setting-note">默认关闭。开启后，缺少原始逐字时间的歌词会按句长计算逐字进度，可能不准确；有原始逐字数据时始终优先使用原数据。此项与调色同时应用于软件内和桌面歌词。</p>
          <CollapsibleSettingsCard section-id="lyric-colors" title="歌词调色与背景" subtitle="展开后调整颜色与透明度" :remember="false">
            <LyricColorPicker :settings="store.overlaySettings" @change="(key, value) => store.updateOverlayField(key, value)" @commit="store.flushLiveSettings()" />
          </CollapsibleSettingsCard>
          <div class="overlay-options">
            <label>歌词大小 <b>{{ store.overlaySettings.fontSize }} sp</b></label>
            <input class="settings-slider" type="range" min="16" max="34" :value="store.overlaySettings.fontSize" @input="store.updateOverlayField('fontSize', Number(($event.target as HTMLInputElement).value))" @change="store.flushLiveSettings()" />
            <label class="settings-detail compact"><span>同步显示译文</span><input type="checkbox" :checked="store.overlaySettings.translation" @change="store.updateOverlayField('translation', ($event.target as HTMLInputElement).checked)" /></label>
          <label class="settings-detail compact"><span>显示下一句</span><input type="checkbox" :checked="store.overlaySettings.nextLine" @change="store.updateOverlayField('nextLine', ($event.target as HTMLInputElement).checked)" /></label>
          <label class="settings-detail compact"><span>锁定歌词位置</span><input type="checkbox" :checked="store.overlaySettings.locked" @change="store.updateOverlayField('locked', ($event.target as HTMLInputElement).checked)" /></label>
          <label class="settings-detail compact"><span>锁定时保留完全不透明（可能阻止下方点击）</span><input type="checkbox" :checked="!store.overlaySettings.touchPassthroughPriority" :disabled="!store.overlaySettings.locked" @change="store.updateOverlayField('touchPassthroughPriority', !($event.target as HTMLInputElement).checked)" /></label>
          <p class="setting-note">默认锁定后允许点击下方，系统会降低窗口整体不透明度，调色数值保留。此前保存的完整不透明设置会保留，取消此项即可改为穿透优先。个别应用仍不接受点击时，可在歌词通知中临时隐藏 30 秒。</p>
            <label class="settings-detail compact"><span>歌词阴影</span><input type="checkbox" :checked="store.overlaySettings.shadowEnabled" @change="store.updateOverlayField('shadowEnabled', ($event.target as HTMLInputElement).checked)" /></label>
          <label>阴影模糊 <b>{{ store.overlaySettings.shadowRadius }} dp</b></label>
          <input class="settings-slider" type="range" min="0" max="8" :value="store.overlaySettings.shadowRadius" @input="store.updateOverlayField('shadowRadius', Number(($event.target as HTMLInputElement).value))" @change="store.flushLiveSettings()" />
          <LyricOffsetControl :model-value="store.overlaySettings.currentLyricOffsetMs ?? 0" :track-key="[store.playback.trackId, store.playback.track?.localUri ?? '', store.playback.track?.localPath ?? '', store.playback.track?.neteaseId ?? ''].join('|')" :disabled="!store.playback.trackId" @update:model-value="store.updateCurrentLyricOffset" @commit="store.flushLiveSettings()" />
          </div>
        </CollapsibleSettingsCard>
        <UserBackupSettings />
        <AppUpdateSettings @about="store.navigateTo('about')" />
      </section>
</template>
