<script setup>
import { ref, watch, inject } from 'vue'
import { api } from '../api.js'
import MarkdownView from './MarkdownView.vue'

const props = defineProps({ id: { type: String, required: true } })
const emit = defineEmits(['navigate', 'changed'])
const toast = inject('toast')

const detail = ref(null)
const tab = ref('frame')
const editing = ref(false)
const drafts = ref({ whatMd: '', whyMd: '', howMd: '' })
const busy = ref(false)
const openFollowup = ref(0)

watch(() => props.id, load, { immediate: true })

async function load() {
  editing.value = false
  openFollowup.value = 0
  tab.value = 'frame'
  try {
    detail.value = await api.get('/api/questions/' + props.id)
  } catch (e) {
    toast(e.message)
  }
}

async function setLevel(lv) {
  if (detail.value.level === lv) return
  try {
    await api.put(`/api/questions/${props.id}/mastery`, { level: lv })
    detail.value.level = lv
    emit('changed')
  } catch (e) {
    toast(e.message)
  }
}

function startEdit() {
  drafts.value = {
    whatMd: detail.value.gen?.whatMd || '',
    whyMd: detail.value.gen?.whyMd || '',
    howMd: detail.value.gen?.howMd || ''
  }
  editing.value = true
}

async function saveEdit() {
  busy.value = true
  try {
    await api.put(`/api/questions/${props.id}/gen`, drafts.value)
    toast('已保存（人工编辑，AI/规则批量不会覆盖）')
    await load()
    editing.value = false
    emit('changed')
  } catch (e) {
    toast(e.message)
  } finally {
    busy.value = false
  }
}

async function regenRule() {
  if (detail.value.gen && !confirm('用规则引擎重新生成会覆盖当前三段式内容，继续？')) return
  busy.value = true
  try {
    await api.post('/api/generate/rule', { questionId: props.id })
    toast('规则引擎已重新生成')
    await load()
  } catch (e) {
    toast(e.message)
  } finally {
    busy.value = false
  }
}

async function runAi(scope, confirmText) {
  if (confirmText && !confirm(confirmText)) return
  busy.value = true
  try {
    await api.post('/api/generate/ai', { scope, questionId: props.id })
    const job = await pollJobDone()
    await load()
    emit('changed')
    if (job && (job.failed > 0 || job.status === 'FAILED' || job.status === 'STOPPED')) {
      toast(`生成未完成：${job.message || job.status}（可重试，或到设置页检查 AI 配置）`)
    } else {
      toast(scope === 'extra' ? 'AI 拓展已生成' : 'AI 三段式已生成')
    }
  } catch (e) {
    toast(e.message)
  } finally {
    busy.value = false
  }
}

async function pollJobDone() {
  while (true) {
    const job = await api.get('/api/job')
    if (!job || !job.running) return job
    await new Promise((r) => setTimeout(r, 1200))
  }
}

const sourceLabel = { rule: '规则生成', ai: 'AI 生成', manual: '人工编辑' }

const sections = [
  { key: 'whatMd', label: '是什么', hint: '概念定义 · 开口第一句' },
  { key: 'whyMd', label: '为什么', hint: '设计动机 · 底层原理' },
  { key: 'howMd', label: '怎么做', hint: '实践要点 · 对比记忆' }
]
</script>

<template>
  <div v-if="detail" class="detail-body">
    <div class="detail-meta">
      <span class="tag">{{ detail.categoryName }}</span>
      <span>第 {{ detail.num }} 题</span>
      <span v-if="detail.gen" class="tag" :class="'source-' + detail.gen.source">
        {{ sourceLabel[detail.gen.source] }}<template v-if="detail.gen.source === 'ai' && detail.gen.model"> · {{ detail.gen.model }}</template>
      </span>
      <span v-if="detail.gen && detail.gen.generatedAt">{{ detail.gen.generatedAt }}</span>
      <div class="detail-actions">
        <button v-if="!editing" class="btn small" :disabled="busy" @click="startEdit">编辑</button>
        <button class="btn small" :disabled="busy" @click="regenRule">规则重生成</button>
        <button
          class="btn small"
          :disabled="busy"
          @click="runAi('content', '调用 AI 重新生成本题三段式（将覆盖现有内容），继续？')"
        >
          AI 重生成
        </button>
      </div>
    </div>

    <h1 class="detail-title">{{ detail.title }}</h1>

    <div class="mastery-switch">
      <button
        v-for="(t, lv) in ['未学习', '模糊', '已掌握']"
        :key="lv"
        :class="{ ['on-l' + lv]: detail.level === Number(lv) }"
        @click="setLevel(Number(lv))"
      >
        {{ t }}
      </button>
    </div>

    <div class="detail-tabs">
      <button :class="{ active: tab === 'frame' }" @click="tab = 'frame'">三段式</button>
      <button :class="{ active: tab === 'origin' }" @click="tab = 'origin'">原答案</button>
      <button :class="{ active: tab === 'related' }" @click="tab = 'related'">
        相关题目 · {{ detail.related.length }}
      </button>
      <button :class="{ active: tab === 'extra' }" @click="tab = 'extra'">
        AI 拓展{{ detail.extra ? '' : ' +' }}
      </button>
    </div>

    <!-- 三段式 -->
    <template v-if="tab === 'frame'">
      <template v-if="editing">
        <div v-for="s in sections" :key="s.key" class="sec-card">
          <div class="sec-spine">{{ s.label }}</div>
          <div class="sec-content">
            <div class="sec-hint">{{ s.hint }}（Markdown）</div>
            <textarea v-model="drafts[s.key]" class="gen-edit"></textarea>
          </div>
        </div>
        <button class="btn primary" :disabled="busy" @click="saveEdit">保存</button>
        <button class="btn" style="margin-left: 8px" :disabled="busy" @click="editing = false">取消</button>
      </template>
      <template v-else-if="detail.gen">
        <div v-for="s in sections" :key="s.key" class="sec-card">
          <div class="sec-spine">{{ s.label }}</div>
          <div class="sec-content">
            <div class="sec-hint">{{ s.hint }}</div>
            <MarkdownView :md="detail.gen[s.key]" />
          </div>
        </div>
      </template>
      <div v-else class="list-empty">尚未生成三段式内容。</div>
    </template>

    <!-- 原答案 -->
    <MarkdownView v-else-if="tab === 'origin'" :md="detail.answerMd" />

    <!-- 相关题目 -->
    <template v-else-if="tab === 'related'">
      <button v-for="r in detail.related" :key="r.id" class="related-item" @click="emit('navigate', r.id)">
        <span class="tag">{{ r.categoryName }}</span>
        <span>{{ r.title }}</span>
      </button>
      <div v-if="!detail.related.length" class="list-empty">暂无关联题目。</div>
    </template>

    <!-- AI 拓展 -->
    <template v-else-if="tab === 'extra'">
      <template v-if="detail.extra">
        <h3 style="font-size: 14px; margin-bottom: 8px; color: var(--ink-soft)">延伸知识点</h3>
        <div v-for="(ins, i) in detail.extra.insights" :key="i" class="insight-card">
          <MarkdownView :md="ins" />
        </div>
        <h3 style="font-size: 14px; margin: 16px 0 4px; color: var(--ink-soft)">可能的追问</h3>
        <div v-for="(f, i) in detail.extra.followups" :key="'f' + i">
          <button class="followup-q" @click="openFollowup = openFollowup === i ? -1 : i">
            {{ i + 1 }}. {{ f.q }}
          </button>
          <div v-if="openFollowup === i" class="followup-a">
            <MarkdownView :md="f.a" />
          </div>
        </div>
        <p class="hint" style="margin-top: 10px">生成于 {{ detail.extra.generatedAt }}</p>
      </template>
      <div v-else class="list-empty">
        还没有 AI 拓展内容。<br /><br />
        <button class="btn primary" :disabled="busy" @click="runAi('extra')">生成本题 AI 拓展</button>
      </div>
    </template>
  </div>
</template>
