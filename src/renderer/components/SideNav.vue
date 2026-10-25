<template>
  <div class="side-nav">
    <side-nav-item
      v-if="enableLibrary"
      :active="isCurrentRoute('/')"
      :data-tip="`${$t('nav.home')}`"
      @click="handleRoute('/')"
    >
      <svg-icon class="icon" icon-class="logo" />
    </side-nav-item>
    <side-nav-item
      v-if="enableLibrary"
      :active="isCurrentRoute('/explore')"
      :data-tip="`${$t('nav.explore')}`"
      @click="handleRoute('/explore')"
    >
      <svg-icon class="icon" icon-class="explore" style="transform: scale(1.4)" />
    </side-nav-item>
    <side-nav-item
      v-if="enableLibrary"
      :active="isCurrentRoute('/library')"
      :data-tip="`${$t('nav.library')}`"
      @click="handleRoute('/library')"
    >
      <svg-icon class="icon" icon-class="library" />
    </side-nav-item>
    <side-nav-item
      v-if="enableStream"
      :active="isCurrentRoute('/stream')"
      :data-tip="`${$t('nav.stream')}`"
      @click="handleRoute('/stream')"
    >
      <svg-icon class="icon" icon-class="stream-icon" style="transform: scale(0.9)"></svg-icon>
    </side-nav-item>
    <side-nav-item
      v-if="isElectron && enableLocal"
      :active="isCurrentRoute('/localMusic')"
      :data-tip="`${$t('nav.localMusic')}`"
      @click="handleRoute('/localMusic')"
    >
      <svg-icon class="icon" icon-class="local-music" />
    </side-nav-item>
    <side-nav-item
      :active="isCurrentRoute('/settings')"
      :data-tip="`${$t('nav.settings')}`"
      @click="handleRoute('/settings')"
    >
      <svg-icon class="icon" icon-class="settings" style="transform: scale(0.9)" />
    </side-nav-item>
  </div>
</template>

<script setup lang="ts">
import SideNavItem from '../../../packages/ui/src/SideNavItem.vue'
import SvgIcon from './SvgIcon.vue'
import { useRoute, useRouter } from 'vue-router'
import { usePluginMusic } from '../store/pluginMusic'
import { storeToRefs } from 'pinia'

const pluginStore = usePluginMusic()
const { enableLibrary, enableStream, enableLocal } = storeToRefs(pluginStore)

const router = useRouter()
const route: any = useRoute()

const handleRoute = (path: string): void => {
  router.push(path)
}

const isElectron = window.env?.isElectron || false

const isCurrentRoute = (path: string): boolean => {
  return path === route.path
}
</script>

<style scoped>
.side-nav {
  position: fixed;
  top: 50%;
  left: 20px;
  padding: 15px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  text-transform: uppercase;
  user-select: none;
  -webkit-app-region: drag;
  z-index: 15;
  background-color: var(--color-secondary-bg);
  border-radius: 12px;
  transform: translate(0, -50%);
}
.side-nav button::before {
    content: '';
    position: absolute;
    top: 50%;
    left: 0;
    border: 8px solid transparent;
    border-right-color: rgb(from var(--color-primary) r g b / 60%);
    transform: translate(60px, -50%);
    z-index: 1;
  }
.side-nav button::after {
    content: attr(data-tip);
    background-color: rgb(from var(--color-primary) r g b / 60%);
    color: white;
    position: absolute;
    top: 50%;
    left: 0;
    width: auto;
    height: 40px;
    padding: 0 16px;
    border-radius: 6px;
    white-space: nowrap;
    line-height: 40px;
    font-size: 16px;
    transform: translate(76px, -50%);
  }
.side-nav button::after,
.side-nav button::before {
    display: none;
  }
.side-nav button:hover:after,
.side-nav button:hover::before {
    display: block;
  }
</style>
