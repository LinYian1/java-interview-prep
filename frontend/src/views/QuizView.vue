<script setup>
import { ref, computed, inject, onMounted } from 'vue'
import { api } from '../api.js'
import MarkdownView from '../components/MarkdownView.vue'

const toast = inject('toast')

const stage = ref('config') // config | run | done
const cats = ref([])
const count = ref(10)
const categoryId = ref('')
const levels = ref([0, 1])

const items = ref([])
const idx = ref(0)
const revealed = ref(false)
const current = ref(null) // 当前题详情（点显示答案时懒加载）
const results = ref([])

onMounted(async () => {
  cats.value = await api.get('/api/categories')
})

async function start() {
  const params = new URLSearchParams({ count: String(count.value), levels: levels.value.join(',') })
  if (categoryId.value) params.set('categoryId', categoryId.value)
  try {
    const data = await api.get('/api/quiz/draw?' + params.toString())
    if (!data.items.length) {
      toast('没有符合筛选条件的题目，放宽筛选再试。')
      return
    }
    items.value = data.items
    idx.value = 0
    revealed.value = false
    current.value = null
    results.value = []
    stage.value = 'run'
  } catch (e) {
    toast(e.message)
  }
}

async function reveal() {
  try {
    current.value = await api.get('/api/questions/' + items.value[idx.value].id)
    revealed.value = true
  } catch (e) {
    toast(e.message)
  }
}

async function mark(remembered) {
  try {
    await api.post('/api/quiz/judge', { questionId: items.value[idx.value].id, remembered })
  } catch (e) {
    toast(e.message)
  }
  results.value.push(remembered)
  if (idx.value + 1 >= items.value.length) {
    stage.value = 'done'
    return
  }
  idx.value++
  revealed.value = false
  current.value = null
}

const passCount = computed(() => results.value.filter(Boolean).length)

function cellClass(i) {
  if (i < results.value.length) return results.value[i] ? 'pass' : 'fail'
  if (stage.value === 'run' && i === idx.value) return 'current'
  return ''
}
</script>

<template>
  <div class="page">
    <!-- 配置 -->
    <div v-if="stage === 'config'" class="quiz-config">
      <h2 style="margin-bottom: 18px">抽题自测</h2>
      <div class="form-row">
        <label class="form-label">题目数量</label>
        <select v-model.number="count">
          <option :value="5">5 题</option>
          <option :value="10">10 题</option>
          <option :value="20">20 题</option>
          <option :value="50">50 题</option>
        </select>
      </div>
      <div class="form-row">
        <label class="form-label">分类范围</label>
        <select v-model="categoryId">
          <option value="">全部分类</option>
          <option v-for="c in cats" :key="c.id" :value="String(c.id)">{{ c.name }}（{{ c.total }}）</option>
        </select>
      </div>
      <div class="form-row">
        <label class="form-label">掌握度范围</label>
        <div class="radio-line">
          <label class="checkbox-line"><input v-model="levels" type="checkbox" :value="0" />未学习</label>
          <label class="checkbox-line"><input v-model="levels" type="checkbox" :value="1" />模糊</label>
          <label class="checkbox-line"><input v-model="levels" type="checkbox" :value="2" />已掌握</label>
        </div>
      </div>
      <button class="btn primary" style="width: 100%; padding: 10px" @click="start">开始作答</button>
      <p class="hint">先自己回忆，再翻开答案对照三段式框架自评。</p>
    </div>

    <!-- 作答 -->
    <div v-else-if="stage === 'run'" class="quiz-wrap">
      <div class="answer-sheet">
        <span
          v-for="(it, i) in items"
          :key="it.id"
          class="sheet-cell"
          :class="cellClass(i)"
          :title="'第 ' + (i + 1) + ' 题'"
        ></span>
      </div>
      <div class="quiz-card">
        <div class="quiz-count">第 {{ idx + 1 }} / {{ items.length }} 题</div>
        <div class="quiz-question">{{ items[idx].title }}</div>

        <template v-if="revealed && current">
          <div class="quiz-answer">
            <template v-if="current.gen">
              <div v-for="s in [
                { key: 'whatMd', label: '是什么' },
                { key: 'whyMd', label: '为什么' },
                { key: 'howMd', label: '怎么做' }
              ]" :key="s.key" style="margin-bottom: 12px">
                <span class="tag" style="color: var(--cinnabar); border-color: var(--cinnabar); margin-right: 8px">{{ s.label }}</span>
                <MarkdownView :md="current.gen[s.key]" />
              </div>
            </template>
            <details>
              <summary style="cursor: pointer; color: var(--ink-soft); font-size: 13px">展开原答案</summary>
              <MarkdownView :md="current.answerMd" />
            </details>
          </div>
        </template>
      </div>
      <div class="quiz-actions">
        <button v-if="!revealed" class="btn primary" style="padding: 10px 34px" @click="reveal">显示答案</button>
        <template v-else>
          <button class="btn bad" @click="mark(false)">没记住</button>
          <button class="btn good" @click="mark(true)">记住了</button>
        </template>
      </div>
    </div>

    <!-- 结果 -->
    <div v-else class="quiz-config">
      <div class="quiz-summary">
        <div class="summary-num">{{ passCount }} / {{ items.length }}</div>
        <p style="color: var(--ink-soft)">记住了 {{ passCount }} 题，没记住 {{ items.length - passCount }} 题。</p>
        <p class="hint">「没记住」的题目已自动标记为模糊，会更容易被再次抽到。</p>
        <br />
        <button class="btn primary" @click="stage = 'config'">再来一轮</button>
      </div>
    </div>
  </div>
</template>
