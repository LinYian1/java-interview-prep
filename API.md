# REST API 文档

八股自习室后端接口。Base URL：`http://localhost:8080`。

- 请求与响应均为 JSON（UTF-8）
- 非法参数 / 业务错误返回对应 HTTP 状态码，响应体为 `{"message": "错误说明"}`
- 常见状态码：`400` 参数或业务校验失败 · `409` 批任务冲突 · `502` AI 网关/解析失败

## 枚举约定

| 字段 | 取值 |
|---|---|
| `level`（掌握度） | `0` 未学习 · `1` 模糊 · `2` 已掌握 |
| `gen.source`（三段式来源） | `rule` 规则引擎 · `ai` 大模型 · `manual` 人工编辑 |
| `scope`（AI 批量范围） | `content` 三段式 · `extra` AI 拓展 · `both` 两者 |
| `job.status` | `RUNNING` · `DONE` · `STOPPED` · `FAILED` |

---

## 一、题库浏览

### GET /api/categories

分类统计列表（含掌握度计数）。

```bash
curl http://localhost:8080/api/categories
```

```json
[
  { "id": 1, "name": "Java 基础", "ord": 1, "total": 22, "mastered": 2, "fuzzy": 1 }
]
```

### GET /api/questions

题目分页列表，支持分类、掌握度、关键词过滤。

| Query 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `categoryId` | long | 不限 | 按分类过滤 |
| `level` | int | 不限 | 按掌握度过滤（0/1/2） |
| `q` | string | — | 关键词，匹配题干与答案 |
| `page` | int | 1 | 页码 |
| `size` | int | 50 | 每页条数，上限 200 |

```bash
curl "http://localhost:8080/api/questions?categoryId=3&q=%E7%BA%BF%E7%A8%8B%E6%B1%A0&page=1&size=20"
```

```json
{
  "total": 5, "page": 1, "size": 20,
  "items": [
    {
      "id": "3-23", "num": 23,
      "title": "线程池的核心参数？",
      "categoryId": 3, "categoryName": "多线程",
      "level": 0,
      "snippet": "…命中关键词的答案摘要…"
    }
  ]
}
```

> `snippet`：去除 Markdown 标记后的摘要；有关键词时定位到首次命中的上下文。

### GET /api/questions/{id}

题目详情，聚合原答案、三段式、AI 拓展、相关题目与掌握度。

```bash
curl http://localhost:8080/api/questions/1-2
```

```json
{
  "id": "1-2", "num": 2, "title": "`==` 和 `equals` 的区别是什么？",
  "answerMd": "- **`==`**：比较对象的**内存地址**…",
  "categoryId": 1, "categoryName": "Java 基础",
  "level": 2,

  "gen": {
    "whatMd": "…是什么（Markdown）…",
    "whyMd": "…为什么…",
    "howMd": "…怎么做…",
    "source": "ai", "model": "deepseek-v4-flash",
    "generatedAt": "2026-08-25 11:55"
  },

  "extra": {
    "insights": ["**Integer 缓存池陷阱**：…", "…"],
    "followups": [ { "q": "追问问题", "a": "参考答案要点(Markdown)" } ],
    "generatedAt": "2026-08-25 11:55"
  },

  "related": [
    { "id": "1-19", "title": "`String` 中 `equals` 和 `==` 的区别？", "categoryName": "Java 基础", "score": 0.61 }
  ]
}
```

> `gen` / `extra` 从未生成时为 `null`。

### PUT /api/questions/{id}/mastery

设置掌握度。

```bash
curl -X PUT http://localhost:8080/api/questions/1-2/mastery \
     -H "Content-Type: application/json" -d '{"level": 2}'
```

响应：`{ "ok": true, "level": 2 }`

### PUT /api/questions/{id}/gen

人工编辑三段式（来源标记为 `manual`；规则/AI 的批量生成不会覆盖）。
内容变更触发重新导入时，manual 内容同样会被作废（因其基于旧题面）。

```bash
curl -X PUT http://localhost:8080/api/questions/1-2/gen \
     -H "Content-Type: application/json" \
     -d '{"whatMd":"…","whyMd":"…","howMd":"…"}'
```

响应：`{ "ok": true }`

### POST /api/questions/{id}/recompute-related

手动触发全库关联重算（导入流程会自动执行，一般无需调用）。

响应：`{ "ok": true, "count": 8 }`（该题当前的相关题数量）

---

## 二、数据导入与规则生成

### POST /api/ingest

重新读取题库文件并增量入库（内容哈希未变的题目保留全部生成数据与用户数据），
随后由规则引擎补齐缺失的三段式。

```bash
curl -X POST http://localhost:8080/api/ingest
```

```json
{
  "ok": true,
  "added": 0, "updated": 3, "removed": 0, "unchanged": 178,
  "ruleFilled": 3
}
```

### GET /api/source

当前题库文件路径。响应：`{ "path": "D:/DEVELOP/doc/必备Java面试题.md" }`

### POST /api/generate/rule

规则引擎生成三段式。

| Body 字段 | 说明 |
|---|---|
| `questionId` | 可选。提供则单题强制重生成（覆盖 rule/ai 结果）；缺省则只刷新「rule 来源或缺失」的题目，不影响 ai/manual |

```bash
curl -X POST http://localhost:8080/api/generate/rule \
     -H "Content-Type: application/json" -d '{"questionId": "1-2"}'
```

响应：`{ "generated": 1 }`

---

## 三、AI 设置与批任务

### GET /api/settings

```json
{
  "baseUrl": "https://ai.lfree.org/bot/xxx",
  "model": "deepseek-v4-flash",
  "proxy": "http://127.0.0.1:7890",
  "rateMs": 600,
  "apiKeyMasked": "sk-••••rUqU",
  "apiKeySet": true
}
```

### PUT /api/settings

| Body 字段 | 说明 |
|---|---|
| `baseUrl` / `model` / `proxy` | 直接覆盖；`proxy` 为空字符串表示清除代理（直连） |
| `rateMs` | 批处理每题间隔毫秒数，最小 0 |
| `apiKey` | 特殊语义：**缺省 = 保留原值；空字符串 = 清除；其他值 = 覆盖** |

```bash
curl -X PUT http://localhost:8080/api/settings \
     -H "Content-Type: application/json" \
     -d '{"model": "deepseek-v4-flash", "proxy": "http://127.0.0.1:7890"}'
```

响应结构与 GET 相同（Key 脱敏显示）。

### POST /api/settings/test

向配置的网关发送一次极短对话验证连通性。响应：`{ "ok": true, "message": "OK" }`
或 `{ "ok": false, "message": "失败原因" }`。

### POST /api/generate/ai

启动后台批任务（单线程串行，同一时刻仅一个任务）。

| Body 字段 | 说明 |
|---|---|
| `scope` | 必填：`content` / `extra` / `both` |
| `questionId` | 可选。提供则只处理该题 |
| `force` | 默认 `false`。为 true 时忽略「已完成」标记重新生成全部 |

断点续跑：非 force 模式自动跳过已有 `ai` 来源三段式 / 已有拓展知识的题目。

```bash
curl -X POST http://localhost:8080/api/generate/ai \
     -H "Content-Type: application/json" \
     -d '{"scope": "both", "force": false}'
```

响应：`{ "started": true }`

可能的错误：`409 已有批任务在运行`；`400 尚未配置 AI`；`400 没有需要生成的题目`。

### GET /api/job

当前任务快照；本会话从未运行过任务时返回 `null`。

```json
{
  "type": "AI-extra:1-2", "status": "DONE",
  "total": 1, "done": 1, "failed": 0,
  "message": "已完成 1/1",
  "running": false
}
```

最近一次任务的终态会持久化到数据库，重启后仍可查询。

### POST /api/job/stop

请求停止当前批任务（正在生成的一道题会完成后停止）。响应：`{ "ok": true }`

---

## 四、抽题自测

### GET /api/quiz/draw

按条件随机抽题，**只返回题目不含答案**（供自测回忆）。

| Query 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `count` | int | 10 | 数量，上限 50 |
| `categoryId` | long | 不限 | 分类范围 |
| `levels` | CSV | `0,1` | 掌握度范围，如 `0,1,2` |

```bash
curl "http://localhost:8080/api/quiz/draw?count=10&levels=0,1&categoryId=3"
```

```json
{ "items": [ { "id": "3-23", "title": "线程池的核心参数？" } ] }
```

### POST /api/quiz/judge

自评判分并联动掌握度：`remembered=true → level 2`（已掌握），`false → level 1`（模糊）。

```bash
curl -X POST http://localhost:8080/api/quiz/judge \
     -H "Content-Type: application/json" \
     -d '{"questionId": "3-23", "remembered": true}'
```

响应：`{ "ok": true, "level": 2 }`

---

## 五、运行日志

### GET /api/logs

读取 `data/logs/app.log` 尾部（倒序收集后按时间正序返回）。

| Query 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `lines` | int | 300 | 返回条数，范围 50~2000 |
| `level` | string | 全部 | `INFO` / `WARN` / `ERROR` |
| `q` | string | — | 关键词包含匹配（不区分大小写） |

```bash
curl "http://localhost:8080/api/logs?lines=100&level=WARN&q=AI"
```

```json
{
  "file": "data\\logs\\app.log",
  "total": 2,
  "lines": [
    "2026-08-25 11:42:32.759 INFO [main] c.i.p.s.RelatedService - 关联推荐重算完成：181 题，1332 条关联",
    "…"
  ]
}
```

---

## 数据模型补充

- **questionId** 格式为 `{分类序号}-{题号}`，如 `3-23` 表示第 3 个分类第 23 题；
  由题库文件结构决定，文件中插入新题会使同分类后续编号顺延（内容哈希机制保证未变题目数据不丢）。
- 所有 Markdown 字段（`answerMd`、`whatMd` 等）保存原始 Markdown 文本，前端负责渲染。
