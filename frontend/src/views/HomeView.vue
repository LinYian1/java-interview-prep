<script setup>
import { ref, watch, onMounted } from 'vue'
import { api } from '../api.js'
import QuestionDetail from '../components/QuestionDetail.vue'

const cats = ref([])
const list = ref([])
const total = ref(0)
const catId = ref(null) // null = 全部
const level = ref(null) // null = 不限
const q = ref('')
const selectedId = ref(null)
let searchTimer

async function loadCats() {
  cats.value = await api.get('/api/categories')
}

async function loadList() {
  const params = new URLSearchParams()
  if (catId.value != null) params.set('categoryId', catId.value)
  if (level.value != null) params.set('level', level.value)
  if (q.value.trim()) params.set('q', q.value.trim())
  const data = await api.get('/api/questions?' + params.toString())
  total.value = data.total
  list.value = data.items
}

watch(q, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(loadList, 300)
})

watch([catId, level], loadList)

function pickCat(id) {
  catId.value = id === 'all' ? null : Number(id)
}

function onBankChanged() {
  loadCats()
  loadList()
}

onMounted(async () => {
  await loadCats()
  await loadList()
})
</script>

<template>
  <div class="page">
    <div class="browse-grid">
      <!-- 分类树 -->
      <aside class="pane">
        <div class="pane-head">
          <div class="pane-title">分类</div>
          <input v-model="q" class="search-input" placeholder="搜索题干 / 答案…" />
          <div style="height: 10px"></div>
          <div class="mastery-filter">
            <button
              v-for="opt in [
                { v: null, t: '全部' },
                { v: 0, t: '未学习' },
                { v: 1, t: '模糊' },
                { v: 2, t: '已掌握' }
              ]"
              :key="String(opt.v)"
              class="chip"
              :class="{ active: level === opt.v }"
              @click="level = opt.v"
            >
              {{ opt.t }}
            </button>
          </div>
        </div>
        <button class="cat-row" :class="{ active: catId === null }" @click="pickCat('all')">
          <span>全部题目</span>
          <span class="cat-count">{{ cats.reduce((s, c) => s + c.total, 0) }}</span>
        </button>
        <button
          v-for="c in cats"
          :key="c.id"
          class="cat-row"
          :class="{ active: catId === c.id }"
          @click="pickCat(c.id)"
        >
          <span>{{ c.name }}</span>
          <span class="cat-count">{{ c.total }}</span>
        </button>
      </aside>

      <!-- 题目列表 -->
      <section class="pane">
        <div class="pane-head">
          <div class="pane-title">题目 · {{ total }}</div>
        </div>
        <template v-if="list.length">
          <button
            v-for="it in list"
            :key="it.id"
            class="q-item"
            :class="{ active: selectedId === it.id }"
            @click="selectedId = it.id"
          >
            <span class="q-item-top">
              <span class="q-num">{{ it.categoryName }} {{ it.num }}.</span>
              <span class="q-title-text">{{ it.title }}</span>
              <span class="dot" :class="'l' + it.level" :title="['未学习', '模糊', '已掌握'][it.level]"></span>
            </span>
            <span class="q-snippet">{{ it.snippet }}</span>
          </button>
        </template>
        <div v-else class="list-empty">没有符合条件的题目，换个关键词或筛选试试。</div>
      </section>

      <!-- 详情 -->
      <section class="pane">
        <QuestionDetail v-if="selectedId" :id="selectedId" @navigate="selectedId = $event" @changed="onBankChanged" />
        <div v-else class="list-empty" style="margin-top: 60px">
          从左侧选择一道题目开始背诵。<br />
          每道题都有「是什么 / 为什么 / 怎么做」三段式答题框架。
        </div>
      </section>
    </div>
  </div>
</template>
