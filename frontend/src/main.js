import { createApp } from 'vue'
import 'highlight.js/styles/github.css'
import './styles.css'
import App from './App.vue'
import { router } from './router.js'

createApp(App).use(router).mount('#app')
