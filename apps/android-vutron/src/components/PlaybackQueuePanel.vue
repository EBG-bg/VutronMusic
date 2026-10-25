<script setup lang="ts">
import { computed } from 'vue'
import { useAndroidShellStore } from '../stores/androidShell'
import type { VutronTrack } from '../platform/vutronPlugin'
import OriginalIcon from './OriginalIcon.vue'
import TrackListPager from './TrackListPager.vue'
import { useProgressiveTracks } from '../composables/libraryPresentation'
const store = useAndroidShellStore()
function keyedTracks(tracks: VutronTrack[]) {
  const occurrences = new Map<string, number>()
  return tracks.map((track, index) => {
    const identity = `${track.id}:${track.localUri ?? track.localPath ?? ''}:${track.playlistVolumeKey ?? ''}`
    const occurrence = occurrences.get(identity) ?? 0
    occurrences.set(identity, occurrence + 1)
    return { track, index, key: `${identity}:${occurrence}` }
  })
}
const insertedTracks = computed(() => keyedTracks(store.playbackQueue.upNext))
const playlistTracks = computed(() => keyedTracks(store.playbackQueue.tracks))
const { visible: visibleInsertedTracks, hasMore: moreInsertedTracks, showMore: showMoreInsertedTracks } = useProgressiveTracks(insertedTracks, () => store.queueOpen)
const { visible: visiblePlaylistTracks, hasMore: morePlaylistTracks, showMore: showMorePlaylistTracks } = useProgressiveTracks(playlistTracks, () => store.queueOpen)
</script>
<template>
  <Transition name="queue-sheet" :duration="250">
    <div v-if="store.queueOpen" class="queue-layer" @click.self="store.queueOpen = false">
      <section class="queue-panel" role="dialog" aria-modal="true" :aria-label="$t('mobile.player.queue')">
        <header><div><h2>{{ $t('mobile.player.queue') }}</h2><small>{{ $t('mobile.common.tracks', { count: store.playbackQueue.tracks.length }) }} · {{ [$t('mobile.player.listLoop'), $t('mobile.player.singleLoop'), $t('mobile.player.shuffle')][store.playbackQueue.mode] }}</small></div><button class="icon-button" type="button" :aria-label="$t('mobile.player.closeQueue')" @click="store.queueOpen = false"><OriginalIcon name="close" /></button></header>
        <div class="queue-scroll">
          <h3>{{ $t('mobile.common.playing') }}</h3>
          <Transition name="queue-current" mode="out-in"><p :key="store.playback.trackId" class="queue-current">{{ store.playback.title || $t('mobile.common.notPlaying') }}</p></Transition>
          <Transition name="queue-section">
            <section v-if="insertedTracks.length" class="queue-inserted-section">
              <div class="queue-section-title"><h3>{{ $t('mobile.player.inserted', { count: insertedTracks.length }) }}</h3><button class="text-button" type="button" @click="store.clearPlayNext()">{{ $t('mobile.player.clearInserted') }}</button></div>
              <TransitionGroup name="queue-item" tag="div" class="queue-items">
                <div v-for="{ track, index, key } in visibleInsertedTracks" :key="key" class="queue-row"><button type="button" class="queue-track" @click="store.playQueueIndex(index, true)"><OriginalIcon name="next" /><span><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') }}</small></span></button><button type="button" class="icon-button" :aria-label="$t('mobile.player.removeInserted', { name: track.name })" @click="store.removeQueueTrack(index, true)"><OriginalIcon name="close" /></button></div>
              </TransitionGroup>
              <TrackListPager :more="moreInsertedTracks" @more="showMoreInsertedTracks" />
            </section>
          </Transition>
          <h3>{{ $t('mobile.player.playlist') }}</h3>
          <TransitionGroup name="queue-item" tag="div" class="queue-items">
            <div v-for="{ track, index, key } in visiblePlaylistTracks" :key="key" class="queue-row" :class="{ current: index === store.playbackQueue.index }"><button type="button" class="queue-track" @click="store.playQueueIndex(index)"><span class="queue-number">{{ index + 1 }}</span><span><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') }}</small></span></button><button type="button" class="icon-button" :aria-label="$t('mobile.player.removeTrack', { name: track.name })" @click="store.removeQueueTrack(index)"><OriginalIcon name="close" /></button></div>
          </TransitionGroup>
          <TrackListPager :more="morePlaylistTracks" @more="showMorePlaylistTracks" />
          <Transition name="queue-section"><p v-if="!playlistTracks.length" class="empty-state">{{ $t('mobile.library.noQueue') }}</p></Transition>
        </div>
      </section>
    </div>
  </Transition>
</template>

<style scoped>
/* ContextMenu.vue uses 125ms opacity/transform; retain rows until leave completes. */
.queue-items { position: relative; }
.queue-row.queue-item-move, .queue-row.queue-item-enter-active, .queue-row.queue-item-leave-active { transition: opacity 125ms ease-out, transform 125ms ease-out, background-color 125ms ease-out, color 125ms ease-out; }
.queue-item-enter-from, .queue-item-leave-to { opacity: 0; transform: translateX(16px); }
.queue-item-leave-active { position: absolute; left: 0; right: 0; pointer-events: none; }
.queue-section-enter-active, .queue-section-leave-active, .queue-current-enter-active, .queue-current-leave-active { transition: opacity 125ms ease-out, transform 125ms ease-out; }
.queue-section-enter-from, .queue-section-leave-to, .queue-current-enter-from, .queue-current-leave-to { opacity: 0; transform: translateY(6px); }
.queue-row { transition: background-color 125ms ease-out, color 125ms ease-out; }
html[data-motion="reduced"] .queue-item-move, html[data-motion="reduced"] .queue-item-enter-active, html[data-motion="reduced"] .queue-item-leave-active, html[data-motion="reduced"] .queue-section-enter-active, html[data-motion="reduced"] .queue-section-leave-active, html[data-motion="reduced"] .queue-current-enter-active, html[data-motion="reduced"] .queue-current-leave-active, html[data-motion="reduced"] .queue-row { transition: none; }
</style>
