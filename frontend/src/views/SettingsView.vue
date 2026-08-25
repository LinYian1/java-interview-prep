<script setup>
import { ref, inject, onMounted, onUnmounted, watch } from 'vue'
import { api } from '../api.js'

const toast = inject('toast')

// AI 配置
const baseUrl = ref('')
const model = ref('')
const proxy = ref('')
const apiKeyInput = ref('')
const apiKeyMasked = ref('')
const apiKeySet = ref(false)
const rateMs = ref(600)
const testResult = ref(null) // {ok, message}
const saving = ref(false)

// 批处理
const scope = ref('both')
const force = ref(false)
const job = ref(null)
let pollTimer = null

// 数据管理
const sourcePath = ref('')
const ingestReport = ref(null)
const ruleCount = ref(null)

async function regenRule() {
  if (!confirm('将用当前规则重新生成全部「规则来源」的三段式（不影响 AI 与人工编辑的内容），继续？')) return
  try {
    const r = await api.post('/api/generate/rule', {})
    ruleCount.value = r.generated
    toast(`规则引擎已更新 ${r.generated} 题`)
  } catch (e) {
    toast(e.message)
  }
}

async function loadSettings() {
  const s = await api.get('/api/settings')
  baseUrl.value = s.baseUrl || ''
  model.value = s.model || ''
  proxy.value = s.proxy || ''
  rateMs.value = s.rateMs ?? 600
  apiKeyMasked.value = s.apiKeyMasked || ''
  apiKeySet.value = !!s.apiKeySet
}

async function save() {
  saving.value = true
  try {
    const body = { baseUrl: baseUrl.value, model: model.value, proxy: proxy.value.trim(), rateMs: rateMs.value }
    if (apiKeyInput.value.trim()) body.apiKey = apiKeyInput.value.trim()
    await api.put('/api/settings', body)
    apiKeyInput.value = ''
    await loadSettings()
    toast('设置已保存')
    testResult.value = null
  } catch (e) {
    toast(e.message)
  } finally {
    saving.value = false
  }
}

async function clearKey() {
  try {
    await api.put('/api/settings', { apiKey: '' })
    apiKeyInput.value = ''
    await loadSettings()
    toast('已清除 API Key')
  } catch (e) {
    toast(e.message)
  }
}

async function test() {
  testResult.value = null
  try {
    testResult.value = await api.post('/api/settings/test')
  } catch (e) {
    testResult.value = { ok: false, message: e.message }
  }
}

async function startBatch() {
  try {
    await api.post('/api/generate/ai', { scope: scope.value, force: force.value })
    toast('批任务已启动')
    await refreshJob()
  } catch (e) {
    toast(e.message)
  }
}

async function stopBatch() {
  try {
    await api.post('/api/job/stop')
    toast('已发送停止指令，当前题目完成后停止')
  } catch (e) {
    toast(e.message)
  }
}

async function refreshJob() {
  try {
    job.value = await api.get('/api/job')
  } catch {
    // 轮询失败忽略，下个周期重试
  }
}

async function reingest() {
  try {
    ingestReport.value = null
    const r = await api.post('/api/ingest')
    ingestReport.value = r
    toast('题库重新导入完成')
  } catch (e) {
    toast(e.message)
  }
}

// 运行日志
const logs = ref([])
const logLines = ref(300)
const logLevel = ref('')
const logQ = ref('')
const autoRefresh = ref(false)
let logTimer = null
let logSearchTimer = null

async function refreshLogs() {
  try {
    const params = new URLSearchParams({ lines: String(logLines.value) })
    if (logLevel.value) params.set('level', logLevel.value)
    if (logQ.value.trim()) params.set('q', logQ.value.trim())
    const d = await api.get('/api/logs?' + params.toString())
    logs.value = d.lines
  } catch (e) {
    toast(e.message)
  }
}

function toggleAutoRefresh() {
  clearInterval(logTimer)
  if (autoRefresh.value) {
    logTimer = setInterval(refreshLogs, 5000)
  }
}

onMounted(async () => {
  await loadSettings()
  await refreshJob()
  sourcePath.value = (await api.get('/api/source')).path
  pollTimer = setInterval(refreshJob, 1500)
  await refreshLogs()
})

onUnmounted(() => {
  clearInterval(pollTimer)
  clearInterval(logTimer)
})

function progressWidth() {
  if (!job.value || !job.value.total) return 0
  return Math.round(((job.value.done + job.value.failed) / job.value.total) * 100)
}

watch([logLines, logLevel], refreshLogs)
watch(logQ, () => {
  clearTimeout(logSearchTimer)
  logSearchTimer = setTimeout(refreshLogs, 400)
})
</script>

<template>
  <div class="page">
    <div class="settings-wrap">
      <!-- AI 配置 -->
      <div class="settings-card">
        <h2>AI 接口（可选）</h2>
        <p class="hint" style="margin-bottom: 14px">
          兼容 OpenAI 接口格式的服务均可：DeepSeek、通义千问、智谱 GLM、Kimi 等。不配置时规则引擎照常工作。
        </p>
        <div class="settings-grid">
          <div class="form-row">
            <label class="form-label">Base URL</label>
            <input v-model="baseUrl" type="text" placeholder="https://api.deepseek.com/v1" />
          </div>
          <div class="form-row">
            <label class="form-label">模型</label>
            <input v-model="model" type="text" placeholder="deepseek-chat" />
          </div>
          <div class="form-row">
            <label class="form-label">HTTP 代理（可选）</label>
            <input v-model="proxy" type="text" placeholder="http://127.0.0.1:7890" />
            <p class="hint">访问 AI 接口需要走代理时填写，留空则直连。</p>
          </div>
        </div>
        <div class="form-row">
          <label class="form-label">API Key（{{ apiKeySet ? '已配置 ' + apiKeyMasked : '未配置' }}）</label>
          <input v-model="apiKeyInput" type="password" :placeholder="apiKeySet ? '留空保持不变' : 'sk-…'" />
        </div>
        <div style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap">
          <button class="btn primary" :disabled="saving" @click="save">保存设置</button>
          <button class="btn" @click="test">测试连接</button>
          <button v-if="apiKeySet" class="btn" @click="clearKey">清除 Key</button>
        </div>
        <p v-if="testResult" class="result-line" :class="testResult.ok ? 'ok' : 'err'">
          {{ testResult.ok ? '连接成功，模型回复：' + testResult.message : '连接失败：' + testResult.message }}
        </p>
      </div>

      <!-- AI 批处理 -->
      <div class="settings-card">
        <h2>AI 批量生成</h2>
        <div class="form-row radio-line">
          <label class="checkbox-line"><input v-model="scope" type="radio" value="content" />三段式</label>
          <label class="checkbox-line"><input v-model="scope" type="radio" value="extra" />AI 拓展</label>
          <label class="checkbox-line"><input v-model="scope" type="radio" value="both" />两者都要</label>
          <label class="checkbox-line"><input v-model="force" type="checkbox" />强制重新生成全部</label>
        </div>
        <div style="display: flex; gap: 10px">
          <button class="btn primary" @click="startBatch">开始生成</button>
          <button v-if="job && job.running" class="btn" @click="stopBatch">停止</button>
        </div>

        <template v-if="job">
          <div class="progress-track">
            <div class="progress-fill" :style="{ width: progressWidth() + '%' }"></div>
          </div>
          <div class="job-log">
            {{ job.status }} · 完成 {{ job.done }} / 失败 {{ job.failed }} / 共 {{ job.total }}
            <template v-if="job.message"><br />{{ job.message }}</template>
          </div>
        </template>
      </div>

      <!-- 运行日志 -->
      <div class="settings-card">
        <h2>运行日志</h2>
        <div class="radio-line" style="margin-bottom: 10px">
          <select v-model.number="logLines" style="width: 110px">
            <option :value="200">200 行</option>
            <option :value="300">300 行</option>
            <option :value="500">500 行</option>
            <option :value="1000">1000 行</option>
          </select>
          <select v-model="logLevel" style="width: 110px">
            <option value="">全部级别</option>
            <option value="INFO">INFO</option>
            <option value="WARN">WARN</option>
            <option value="ERROR">ERROR</option>
          </select>
          <input v-model="logQ" type="text" placeholder="按关键词过滤…" style="flex: 1; min-width: 140px" />
          <label class="checkbox-line"><input v-model="autoRefresh" type="checkbox" @change="toggleAutoRefresh" />自动刷新</label>
          <button class="btn small" @click="refreshLogs">刷新</button>
        </div>
        <pre class="log-view">{{ logs.length ? logs.join('\n') : '暂无日志' }}</pre>
      </div>

      <!-- 规则引擎与数据 -->
      <div class="settings-card">
        <h2>数据管理</h2>
        <p class="hint" style="margin-bottom: 10px">题库文件：{{ sourcePath }}</p>
        <div style="display: flex; gap: 10px; flex-wrap: wrap">
          <button class="btn" @click="reingest">重新导入题库文件</button>
          <button class="btn" @click="regenRule">规则引擎重刷三段式</button>
        </div>
        <p v-if="ruleCount !== null" class="result-line ok">规则引擎已更新 {{ ruleCount }} 题</p>
        <p v-if="ingestReport" class="result-line ok">
          新增 {{ ingestReport.added }} · 更新 {{ ingestReport.updated }} · 删除 {{ ingestReport.removed }} · 未变 {{ ingestReport.unchanged }} · 规则补齐 {{ ingestReport.ruleFilled }}
        </p>
        <p class="hint" style="margin-top: 10px">
          导入是增量的：内容没变的题目保留已生成的三段式、掌握度与人工编辑。
        </p>
      </div>
    </div>
  </div>
</template>
