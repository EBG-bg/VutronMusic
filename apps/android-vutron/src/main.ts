import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import './style.css'
import './platform/appearance'
import { i18n } from './platform/i18n'
import { installOriginalButtonPress } from './platform/originalButtonPress'

const removeButtonPress = installOriginalButtonPress()
if (import.meta.hot) import.meta.hot.dispose(removeButtonPress)
createApp(App).use(createPinia()).use(i18n).mount('#app')
