import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import './style.css'
import './platform/appearance'
import { installOriginalButtonPress } from './platform/originalButtonPress'

const removeButtonPress = installOriginalButtonPress()
if (import.meta.hot) import.meta.hot.dispose(removeButtonPress)
createApp(App).use(createPinia()).mount('#app')
