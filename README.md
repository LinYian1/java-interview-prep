# 八股自习室 · Java 面试背诵工作台

本地运行的单机 Web 应用，用于背诵 Java 面试八股文。以本地 Markdown 题库为唯一数据源，
对每道题生成「是什么 / 为什么 / 怎么做」三段式答题框架，提供全文搜索、库内关联推荐、
抽题自测与掌握度管理；可选接入任意 OpenAI 兼容大模型接口增强生成质量。

## 功能特性

- **题库导入**：解析 Markdown 题库（`## 分类` / `### 题目` 结构），按内容哈希增量更新，
  文件变更不丢失已生成的三段式、AI 拓展与掌握度数据
- **三段式答题框架**：
  - *规则引擎*（默认可用，零依赖零成本）：启发式分类——强信号门槛、同级列表成段共桶、
    标题先验，概念题不被误拆
  - *AI 增强*（可选）：OpenAI 兼容接口（DeepSeek / Qwen / GLM / Kimi 等），支持 SSE 流式、
    HTTP 代理、失败自动重试 ×3、JSON 容错解析
  - 人工编辑优先级最高，批量生成不会覆盖 manual 来源的内容
- **AI 拓展知识**：延伸知识点 + 高频追问预测（含答案要点）
- **搜索与关联**：题干/答案全文检索（命中上下文高亮）；基于关键词余弦相似度的库内相关题推荐
- **背诵辅助**：未学习 / 模糊 / 已掌握三级标记；抽题自测（答题卡进度），自评自动联动掌握度
- **批处理任务**：全库 AI 批量生成，支持进度条、限速、中途停止、断点续跑（已完成不重复计费）
- **运行日志**：文件落盘 + 设置页在线查看（级别 / 关键词过滤、自动刷新）

## 快速开始

### 环境要求

| 用途 | 要求 |
|---|---|
| 运行 | JRE/JDK 17 或 21 |
| 构建（后端） | Gradle 8.0（**必须用 JDK 17 运行 Gradle**，见 FAQ）+ JDK 17 |
| 构建（前端） | Node.js ≥ 18 |

### 一键启动（已有 jar）

```bash
cd java-interview-prep
java -jar build/libs/interview-prep.jar
```

浏览器访问 <http://localhost:8080>。首次启动会自动解析题库并入库。

> 请在项目目录下启动：数据库等文件相对当前目录生成。

### 从源码构建

> 克隆后需先创建 `gradle.properties`（已被 gitignore，因 JDK 路径因机器而异）：
>
> ```properties
> org.gradle.java.home=D:/你的JDK17路径
> ```
>
> 若你的 Gradle ≥ 8.5 或运行环境本就是 JDK ≤ 17，可省略此项。

```bash
# 1. 构建前端（产物直接写入后端 static 目录）
cd frontend
npm install
npm run build

# 2. 打包后端单 jar
cd ..
gradle bootJar        # 产物：build/libs/interview-prep.jar
```

### 开发模式

后端以 jar 方式常驻（8080），前端热更新：

```bash
cd frontend
npm run dev           # Vite 开发服务器 :5173，/api 自动代理到 8080
```

## 配置

### application.yml（src/main/resources）

```yaml
server:
  port: 8080                      # 服务端口
app:
  source-md: D:/DEVELOP/doc/必备Java面试题.md   # 题库文件路径
  ingest-on-startup: true         # 启动时自动增量导入
```

也可用命令行覆盖：

```bash
java -jar interview-prep.jar --app.source-md=D:/path/to/你的题库.md --spring.datasource.url=jdbc:sqlite:D:/绝对路径/data.db
```

### AI 设置（界面「设置」页填写，无需改配置文件）

| 项 | 说明 |
|---|---|
| Base URL | OpenAI 兼容网关地址，如 `https://api.deepseek.com/v1` |
| 模型 | 如 `deepseek-chat`。**务必选响应快的模型**，慢模型长输出会被网关超时掐断 |
| HTTP 代理 | 可选，如 `http://127.0.0.1:7890`，留空直连 |
| API Key | 保存后脱敏显示；留空提交表示保持不变，「清除 Key」按钮可删除 |

不配置 AI 时所有基础功能照常可用（规则引擎兜底）。

## 数据存储

```
data/
├── interview.db     # SQLite 单文件数据库（题目/生成内容/掌握度/设置）
└── logs/
    └── app.log      # 运行日志（按天+10MB 滚动，保留 7 天）
```

- md 题库文件是题面的**唯一权威来源**，数据库只是派生缓存
- 备份 = 复制 `data/interview.db` 一个文件

## 目录结构

```
java-interview-prep/
├── build.gradle                 # Spring Boot 3.3.13 + SQLite
├── gradle.properties            # org.gradle.java.home 指向 JDK17（构建必需）
├── src/main/java/com/interview/prep/
│   ├── md/                      # Markdown 解析器
│   ├── service/                 # 导入 / 规则引擎 / 关联推荐 / AI / 批任务
│   ├── dao/                     # JdbcTemplate 数据访问
│   └── web/                     # REST 控制器
├── src/main/resources/
│   ├── application.yml          # 应用配置
│   ├── schema.sql               # 建表脚本（幂等）
│   └── logback-spring.xml       # 日志配置
├── frontend/                    # Vue 3 + Vite 前端源码
└── data/                        # 运行时生成：数据库与日志
```

## 常见问题

**Q：为什么 Gradle 必须用 JDK 17 运行？**
Gradle 8.0 的构建脚本类加载器无法处理 Java 21 字节码（major version 65），
而 Spring Boot 3.3 的依赖包含 Java 21 版本的类文件。`gradle.properties` 中
`org.gradle.java.home=D:/DEVELOP/jdk/jdk17` 已固定；因此 **Spring Boot 锁定 3.3.13，
不要随意升级 Boot 版本**，除非同步升级 Gradle ≥ 8.5。

**Q：AI 批处理一直 504？**
多为网关对慢模型长输出的超时（如 Cloudflare 回源超时）。解决：换更快的模型；
本应用已内置 SSE 流式、失败重试 ×3 与可选代理来提高通过率。可在「设置 → 运行日志」
中过滤 ERROR/WARN 定位具体原因。

**Q：换了/更新了题库文件怎么办？**
覆盖 `app.source-md` 指向的文件后重启即可（或设置页点「重新导入题库文件」）。
内容没变的题目保留一切生成数据；内容变化的题目自动作废旧的三段式并重新生成。

**Q：如何查看运行日志？**
设置页「运行日志」卡片，或直接看 `data/logs/app.log`；
接口方式见 [API.md](API.md) 中的 `/api/logs`。

## 接口文档

全部 REST 接口详见 [API.md](API.md)。
