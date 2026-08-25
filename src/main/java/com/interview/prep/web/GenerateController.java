package com.interview.prep.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.interview.prep.dao.SettingsDao;
import com.interview.prep.service.AiService;
import com.interview.prep.service.IngestService;
import com.interview.prep.service.JobManager;
import com.interview.prep.service.RuleEngineService;

@RestController
@RequestMapping("/api")
public class GenerateController {

    private final IngestService ingestService;
    private final RuleEngineService ruleEngineService;
    private final AiService aiService;
    private final JobManager jobManager;
    private final SettingsDao settingsDao;

    public GenerateController(IngestService ingestService, RuleEngineService ruleEngineService,
                              AiService aiService, JobManager jobManager, SettingsDao settingsDao) {
        this.ingestService = ingestService;
        this.ruleEngineService = ruleEngineService;
        this.aiService = aiService;
        this.jobManager = jobManager;
        this.settingsDao = settingsDao;
    }

    // ---------- 数据导入 ----------

    @PostMapping("/ingest")
    public Map<String, Object> ingest() {
        try {
            var report = ingestService.ingest();
            int filled = ruleEngineService.generateMissing();
            return Map.of("ok", true, "added", report.added(), "updated", report.updated(),
                    "removed", report.removed(), "unchanged", report.unchanged(),
                    "ruleFilled", filled);
        } catch (java.io.IOException e) {
            throw BizException.bad("读取题库文件失败：" + e.getMessage());
        }
    }

    @GetMapping("/source")
    public Map<String, Object> source() {
        return Map.of("path", ingestService.sourcePath());
    }

    // ---------- 规则引擎 ----------

    /** body: {questionId?: String}。带 questionId 重生成单题；否则只重刷 rule 来源/缺失的题目 */
    @PostMapping("/generate/rule")
    public Map<String, Object> generateRule(@RequestBody(required = false) Map<String, String> body) {
        if (body != null && body.get("questionId") != null && !body.get("questionId").isBlank()) {
            ruleEngineService.regenerateOne(body.get("questionId").strip());
            return Map.of("generated", 1);
        }
        int n = ruleEngineService.regenerateRuleOnly();
        return Map.of("generated", n);
    }

    // ---------- AI 生成与批任务 ----------

    /** body: {scope: content|extra|both, questionId?, force?} */
    @PostMapping("/generate/ai")
    public Map<String, Object> generateAi(@RequestBody Map<String, Object> body) {
        String scope = String.valueOf(body.getOrDefault("scope", "both"));
        if (!Set.of("content", "extra", "both").contains(scope)) {
            throw BizException.bad("scope 必须为 content / extra / both");
        }
        String questionId = (String) body.get("questionId");
        boolean force = Boolean.TRUE.equals(body.get("force"));
        aiService.startBatch(scope,
                questionId == null || questionId.isBlank() ? null : questionId.strip(), force);
        return Map.of("started", true);
    }

    @GetMapping("/job")
    public JobManager.Snapshot job() {
        return jobManager.snapshot();
    }

    @PostMapping("/job/stop")
    public Map<String, Object> stopJob() {
        jobManager.requestStop();
        return Map.of("ok", true);
    }

    // ---------- AI 设置 ----------

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        var all = settingsDao.all();
        String key = all.get(SettingsDao.AI_API_KEY);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("baseUrl", all.get(SettingsDao.AI_BASE_URL));
        resp.put("model", all.get(SettingsDao.AI_MODEL));
        resp.put("proxy", all.get(SettingsDao.AI_PROXY));
        resp.put("rateMs", settingsDao.getInt(SettingsDao.AI_RATE_MS, 600));
        resp.put("concurrency", settingsDao.getInt(SettingsDao.AI_CONCURRENCY, 3));
        resp.put("apiKeyMasked", mask(key));
        resp.put("apiKeySet", key != null && !key.isBlank());
        return resp;
    }

    /** apiKey: null=保留原值，""=清除，其他=覆盖 */
    @PutMapping("/settings")
    public Map<String, Object> putSettings(@RequestBody Map<String, Object> body) {
        putIfPresent(body, "baseUrl", SettingsDao.AI_BASE_URL);
        putIfPresent(body, "model", SettingsDao.AI_MODEL);
        putIfPresent(body, "proxy", SettingsDao.AI_PROXY);
        if (body.containsKey("rateMs")) {
            try {
                int rate = Integer.parseInt(String.valueOf(body.get("rateMs")));
                settingsDao.put(SettingsDao.AI_RATE_MS, String.valueOf(Math.max(rate, 0)));
            } catch (NumberFormatException ignored) {
                // 非法值忽略，保持原配置
            }
        }
        if (body.containsKey("apiKey")) {
            Object v = body.get("apiKey");
            String s = v == null ? null : String.valueOf(v).strip();
            if (s == null || s.isEmpty()) {
                settingsDao.delete(SettingsDao.AI_API_KEY);
            } else {
                settingsDao.put(SettingsDao.AI_API_KEY, s);
            }
        }
        return getSettings();
    }

    @PostMapping("/settings/test")
    public AiService.TestResult testSettings() {
        return aiService.test();
    }

    private void putIfPresent(Map<String, Object> body, String field, String settingKey) {
        if (body.containsKey(field)) {
            Object v = body.get(field);
            settingsDao.put(settingKey, v == null ? "" : String.valueOf(v).strip());
        }
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 8) {
            return "••••";
        }
        return key.substring(0, 3) + "••••" + key.substring(key.length() - 4);
    }
}
