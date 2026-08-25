<script setup>
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/common'

const props = defineProps({ md: { type: String, default: '' } })

const mdit = new MarkdownIt({
  html: false,
  linkify: true,
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre><code class="hljs">${hljs.highlight(code, { language: lang }).value}</code></pre>`
      } catch {
        // 高亮失败退回纯文本
      }
    }
    return `<pre><code class="hljs">${mdit.utils.escapeHtml(code)}</code></pre>`
  }
})

function render(src) {
  return mdit.render(src || '')
}
</script>

<template>
  <div class="md-body" v-html="render(props.md)"></div>
</template>
