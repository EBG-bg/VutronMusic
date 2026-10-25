import { ref, watch } from 'vue'

const savedView = localStorage.getItem('vutron:playlist-view')
const savedColumns = Number(localStorage.getItem('vutron:playlist-columns'))
export const playlistView = ref<'cards' | 'list'>(savedView === 'list' ? 'list' : 'cards')
export const playlistColumns = ref(Number.isInteger(savedColumns) && savedColumns >= 1 && savedColumns <= 6 ? savedColumns : 2)
watch([playlistView, playlistColumns], () => {
  localStorage.setItem('vutron:playlist-view', playlistView.value)
  localStorage.setItem('vutron:playlist-columns', String(playlistColumns.value))
}, { immediate: true })
