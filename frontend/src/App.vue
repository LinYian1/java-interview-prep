<script setup>
import { ref, provide } from 'vue'

const msg = ref('')
let timer

function toast(text) {
  msg.value = text
  clearTimeout(timer)
  timer = setTimeout(() => (msg.value = ''), 2800)
}

provide('toast', toast)
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="brand"><span class="brand-seal">背</span>八股自习室</div>
      <nav class="app-nav">
        <router-link to="/browse">题库</router-link>
        <router-link to="/quiz">自测</router-link>
        <router-link to="/settings">设置</router-link>
      </nav>
    </header>
    <main class="app-main">
      <router-view />
    </main>
    <div v-if="msg" class="toast">{{ msg }}</div>
  </div>
</template>
