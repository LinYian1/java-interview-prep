package com.interview.prep.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.prep.dao.BankDao;
import com.interview.prep.dao.GenDao;
import com.interview.prep.dao.SettingsDao;
import com.interview.prep.web.BizException;

/**
 * OpenAI 兼容接口接入（DeepSeek/Qwen/GLM/Kimi 等）。
 * 三段式与拓展知识各自独立提示词；批处理经 JobManager 串行执行、限速、断点续跑
 * （续跑 = 已有 ai 来源结果的题目自动跳过，除非 force）。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private static final String SYSTEM_PROMPT =
            "你是资深的 Java 面试官与教练。所有内容使用简体中文 Markdown。"
                    + "严格按要求输出 JSON，不要输出 JSON 以外的任何文字。";

    private static final String PROMPT_THREE = """
            针对下面的 Java 面试题生成三段式答题框架：
            1. what：概念定义与核心结论，作为面试开口第一句，简洁准确；
            2. why：设计动机、底层原理或存在的理由，体现技术深度；
            3. how：使用方式、实践要点、代码示例或对比记忆技巧。
            每段是 Markdown 字符串（可用列表/代码块），三段合计不超过 500 字，不要复述题目。

            【题目】%s

            【原答案】%s

            输出 JSON：{"what":"...","why":"...","how":"..."}
            """;

    private static final String PROMPT_EXTRA = """
            针对下面的 Java 面试题生成拓展学习材料：
            1. insights：3~5 条延伸知识点（字符串数组），每条以 **小标题** 开头并给出说明，
               覆盖该主题的进阶原理、常见坑、实战建议；
            2. followups：面试官最可能追问的 3 个问题（对象数组），元素为 {"q":"追问问题","a":"参考答案要点(Markdown)"}。

            【题目】%s

            【原答案】%s

            输出 JSON：{"insights":["..."],"followups":[{"q":"...","a":"..."}]}
            """;

    private static final int MAX_ANSWER_CHARS = 6000;

    public record AiConfig(String baseUrl, String apiKey, String model) {
        public boolean ready() {
            return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
        }

        static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    private final SettingsDao settingsDao;
    private final GenDao genDao;
    private final BankDao bankDao;
    private final JobManager jobs;
    private final ObjectMapper om;

    /** 按代理配置构建专用客户端；代理值变化时重建 */
    private volatile HttpClient proxiedClient;
    private volatile String proxiedClientKey = null;

    private HttpClient client() {
        String proxy = settingsDao.get(SettingsDao.AI_PROXY);
        String key = proxy == null ? "" : proxy.strip();
        HttpClient c = proxiedClient;
        if (c != null && key.equals(proxiedClientKey)) {
            return c;
        }
        synchronized (this) {
            if (proxiedClient != null && key.equals(proxiedClientKey)) {
                return proxiedClient;
            }
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
            if (!key.isEmpty()) {
                try {
                    URI u = URI.create(key.contains("://") ? key : "http://" + key);
                    b.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(u.getHost(), u.getPort())));
                } catch (Exception e) {
                    throw BizException.bad("代理地址无效：" + key);
                }
            }
            proxiedClient = b.build();
            proxiedClientKey = key;
            return proxiedClient;
        }
    }

    public AiService(SettingsDao settingsDao, GenDao genDao, BankDao bankDao,
                     JobManager jobs, ObjectMapper om) {
        this.settingsDao = settingsDao;
        this.genDao = genDao;
        this.bankDao = bankDao;
        this.jobs = jobs;
        this.om = om;
    }

    public AiConfig config() {
        var all = settingsDao.all();
        return new AiConfig(all.get(SettingsDao.AI_BASE_URL), all.get(SettingsDao.AI_API_KEY),
                all.get(SettingsDao.AI_MODEL));
    }

    /** 批处理并发数，范围 1~10，默认 3 */
    private int concurrency() {
        return Math.max(1, Math.min(settingsDao.getInt(SettingsDao.AI_CONCURRENCY, 3), 10));
    }

    /** 全局节流：并发下同样保证相邻请求的起始间隔不小于 rateMs */
    private static final class Throttle {
        private final long minInterval;
        private long last;

        Throttle(long minInterval) {
            this.minInterval = minInterval;
        }

        synchronized void waitTurn() throws InterruptedException {
            long wait = last + minInterval - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            last = System.currentTimeMillis();
        }
    }

    public record TestResult(boolean ok, String message) {}

    public TestResult test() {
        try {
            String reply = chat("你是回声测试助手。", "请只回复两个字符：OK");
            return new TestResult(true, reply);
        } catch (Exception e) {
            return new TestResult(false, e.getMessage());
        }
    }

    /**
     * 调用 /chat/completions（SSE 流式），拼接首个 choice 的全部增量文本。
     * 慢模型的长输出在流式下不会被网关的空闲超时掐断。
     */
    public String chat(String system, String user) throws Exception {
        AiConfig c = config();
        if (!c.ready()) {
            throw new BizException(400, "尚未配置 AI（baseUrl / apiKey / model），请在设置页填写");
        }
        String url = c.baseUrl().replaceAll("/+$", "") + "/chat/completions";
        Map<String, Object> body = Map.of(
                "model", c.model(),
                "temperature", 0.3,
                "stream", true,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + c.apiKey())
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<java.io.InputStream> resp =
                client().send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            String err;
            try (var es = resp.body()) {
                err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new BizException(502, "AI 接口返回 HTTP " + resp.statusCode() + "：" + abbrev(err));
        }

        StringBuilder content = new StringBuilder();
        StringBuilder rawAll = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawAll.append(line).append('\n');
                String trimmed = line.strip();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String payload = trimmed.substring(5).strip();
                if (payload.equals("[DONE]")) {
                    break;
                }
                if (payload.isEmpty() || !payload.startsWith("{")) {
                    continue;
                }
                try {
                    JsonNode node = om.readTree(payload);
                    content.append(node.path("choices").path(0).path("delta").path("content").asText(""));
                } catch (Exception ignore) {
                    // 单帧解析失败跳过，不影响整体
                }
            }
        }

        if (content.isEmpty()) {
            // 网关忽略 stream 参数直接回整段 JSON 时按非流式解析兜底
            content.append(nonStreamFallback(rawAll.toString()));
        }
        String result = content.toString().strip();
        if (result.isEmpty()) {
            throw new BizException(502, "AI 返回内容为空");
        }
        return result;
    }

    private String nonStreamFallback(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        try {
            JsonNode node = om.readTree(raw.substring(start, end + 1));
            return node.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** 网关 5xx / 网络抖动自动重试（配置类 400 错误不重试），应对不稳定的中转服务 */
    private String chatWithRetry(String system, String user) throws InterruptedException {
        final int maxAttempts = 3;
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return chat(system, user);
            } catch (BizException e) {
                if (e.status() == 400) {
                    throw e;
                }
                last = e;
            } catch (Exception e) {
                last = e;
            }
            log.warn("AI 调用失败（第 {}/{} 次），稍后重试: {}", attempt, maxAttempts,
                    last == null ? "" : last.getMessage());
            if (attempt < maxAttempts) {
                boolean throttled = last != null
                        && String.valueOf(last.getMessage()).contains("HTTP 429");
                Thread.sleep(throttled ? 8000L * attempt : 2500L * attempt); // 网关限流时退避更久
            }
        }
        if (last instanceof BizException b) {
            throw new BizException(b.status(), "已重试 " + maxAttempts + " 次仍失败：" + b.getMessage());
        }
        throw new BizException(502, "已重试 " + maxAttempts + " 次仍失败：" + (last == null ? "未知错误" : last.getMessage()));
    }

    // ---------- 单题生成 ----------

    public void generateThreePart(String questionId) {
        var q = bankDao.findQuestion(questionId)
                .orElseThrow(() -> BizException.bad("题目不存在: " + questionId));
        try {
            String raw = chatWithRetry(SYSTEM_PROMPT, PROMPT_THREE.formatted(q.title(), trunc(q.answerMd())));
            JsonNode j = extractJson(raw);
            genDao.saveGenerated(questionId, j.path("what").asText(""), j.path("why").asText(""),
                    j.path("how").asText(""), "ai", config().model());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(502, "生成失败：" + e.getMessage());
        }
    }

    public void generateExtra(String questionId) {
        var q = bankDao.findQuestion(questionId)
                .orElseThrow(() -> BizException.bad("题目不存在: " + questionId));
        try {
            String raw = chatWithRetry(SYSTEM_PROMPT, PROMPT_EXTRA.formatted(q.title(), trunc(q.answerMd())));
            JsonNode j = extractJson(raw);
            List<String> insights = new ArrayList<>();
            j.path("insights").forEach(n -> insights.add(n.asText()));
            List<Map<String, String>> followups = new ArrayList<>();
            j.path("followups").forEach(n -> followups.add(Map.of(
                    "q", n.path("q").asText(""),
                    "a", n.path("a").asText(""))));
            genDao.saveExtra(questionId, om.writeValueAsString(insights), om.writeValueAsString(followups));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(502, "生成失败：" + e.getMessage());
        }
    }

    /**
     * 启动批任务。scope: content=三段式 extra=AI 拓展 both=两者；
     * questionId 非空则只处理该题，否则处理全部待生成题目。
     *
     * @return false 表示已有任务在跑
     */
    public boolean startBatch(String scope, String questionId, boolean force) {
        if (!config().ready()) {
            throw BizException.bad("尚未配置 AI，请先在设置页填写并保存");
        }
        boolean doContent = "content".equals(scope) || "both".equals(scope);
        boolean doExtra = "extra".equals(scope) || "both".equals(scope);

        List<String> targets;
        int total;
        if (questionId != null) {
            targets = List.of(questionId);
            total = 1;
        } else {
            var set = new LinkedHashSet<String>();
            if (doContent) {
                set.addAll(genDao.idsNeedingContent(force));
            }
            if (doExtra) {
                set.addAll(genDao.idsNeedingExtra(force));
            }
            targets = new ArrayList<>(set);
            total = targets.size();
        }
        if (total == 0) {
            throw BizException.bad("没有需要生成的题目（如需重新生成全部请勾选强制模式）");
        }

        String type = "AI-" + scope + (questionId != null ? ":" + questionId : "")
                + " x" + concurrency();
        boolean started = jobs.tryStart(type, total, control -> {
            int rateMs = settingsDao.getInt(SettingsDao.AI_RATE_MS, 600);
            int workers = concurrency();
            Throttle throttle = new Throttle(rateMs);
            AtomicInteger done = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            AtomicReference<String> lastError = new AtomicReference<>();

            // 每道题一个任务，线程池大小即并发数；stop 后未开始的任务直接跳过
            ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
                Thread t = new Thread(r, "ai-worker");
                t.setDaemon(true);
                return t;
            });
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (String id : targets) {
                    futures.add(pool.submit(() -> {
                        if (control.stopped()) {
                            return;
                        }
                        try {
                            throttle.waitTurn();
                            if (control.stopped()) {
                                return;
                            }
                            if (doContent && needsContent(id, force)) {
                                generateThreePart(id);
                            }
                            if (doExtra && (force || genDao.findExtra(id) == null)) {
                                generateExtra(id);
                            }
                            done.incrementAndGet();
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            lastError.set(e.getMessage());
                            log.warn("AI 生成失败 {}: {}", id, e.getMessage());
                        }
                        control.progress(done.get(), failed.get(),
                                "已完成 " + done.get() + "/" + targets.size()
                                        + "（并发 " + workers + "）"
                                        + (lastError.get() != null ? "，最近错误：" + abbrev(lastError.get()) : ""));
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(); // 等全部任务结束（含失败），stop 由任务内部短路
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                pool.shutdownNow();
            }
        });
        if (!started) {
            throw new BizException(409, "已有批任务在运行，请等待完成或先停止");
        }
        return true;
    }

    private boolean needsContent(String id, boolean force) {
        if (force) {
            return true;
        }
        var c = genDao.findContent(id);
        return c == null || !"ai".equals(c.source());
    }

    // ---------- 工具 ----------

    private JsonNode extractJson(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                s = s.substring(firstNewline + 1, lastFence);
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BizException(502, "AI 返回内容中未找到 JSON：" + abbrev(raw));
        }
        String json = s.substring(start, end + 1);
        try {
            return om.readTree(json);
        } catch (Exception first) {
            // 模型常在字符串值里输出未转义的换行等控制字符，放宽校验后重试
            try {
                return om.reader()
                        .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                        .readTree(json);
            } catch (Exception second) {
                throw new BizException(502, "AI 返回的 JSON 无法解析：" + abbrev(second.getMessage()));
            }
        }
    }

    private static String trunc(String s) {
        return s.length() <= MAX_ANSWER_CHARS ? s : s.substring(0, MAX_ANSWER_CHARS) + "\n…(已截断)";
    }

    private static String abbrev(String s) {
        if (s == null) {
            return null;
        }
        s = s.replace("\n", " ");
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
