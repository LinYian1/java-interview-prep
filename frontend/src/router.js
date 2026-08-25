import { createRouter, createWebHashHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import QuizView from './views/QuizView.vue'
import SettingsView from './views/SettingsView.vue'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/browse' },
    { path: '/browse', component: HomeView },
    { path: '/quiz', component: QuizView },
    { path: '/settings', component: SettingsView }
  ]
})
