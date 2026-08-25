package com.interview.prep.web;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.prep.dao.BankDao;
import com.interview.prep.dao.BankDao.CategoryStat;
import com.interview.prep.dao.BankDao.DetailRow;
import com.interview.prep.dao.BankDao.ListItem;
import com.interview.prep.dao.GenDao;
import com.interview.prep.service.RelatedService;

@RestController
@RequestMapping("/api")
public class BrowseController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BankDao bankDao;
    private final GenDao genDao;
    private final RelatedService relatedService;
    private final ObjectMapper om;

    public BrowseController(BankDao bankDao, GenDao genDao, RelatedService relatedService,
                            ObjectMapper om) {
        this.bankDao = bankDao;
        this.genDao = genDao;
        this.relatedService = relatedService;
        this.om = om;
    }

    @GetMapping("/categories")
    public List<CategoryStat> categories() {
        return bankDao.categories();
    }

    @GetMapping("/questions")
    public Map<String, Object> questions(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);
        String like = (q == null || q.isBlank()) ? null : BankDao.escapeLike(q.strip());

        var filter = new BankDao.SearchFilter(categoryId, level, like);
        int total = bankDao.countSearch(filter);
        List<ListItem> items = total == 0 ? List.of()
                : bankDao.search(filter, (safePage - 1) * safeSize, safeSize);

        var answers = bankDao.answersOf(items.stream().map(ListItem::id).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ListItem it : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", it.id());
            row.put("num", it.num());
            row.put("title", it.title());
            row.put("categoryId", it.categoryId());
            row.put("categoryName", it.categoryName());
            row.put("level", it.level());
            row.put("snippet", snippet(answers.get(it.id()), q));
            rows.add(row);
        }
        return Map.of("total", total, "page", safePage, "size", safeSize, "items", rows);
    }

    @GetMapping("/questions/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        DetailRow d = bankDao.detail(id);
        if (d == null) {
            throw BizException.bad("题目不存在: " + id);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", d.id());
        resp.put("num", d.num());
        resp.put("title", d.title());
        resp.put("answerMd", d.answerMd());
        resp.put("categoryId", d.categoryId());
        resp.put("categoryName", d.categoryName());
        resp.put("level", d.level());

        if (d.whatMd() != null) {
            var gen = new LinkedHashMap<String, Object>();
            gen.put("whatMd", d.whatMd());
            gen.put("whyMd", d.whyMd());
            gen.put("howMd", d.howMd());
            gen.put("source", d.source());
            gen.put("model", d.model());
            gen.put("generatedAt",
                    d.generatedAt() == null ? null : TS.format(d.generatedAt().toLocalDateTime()));
            resp.put("gen", gen);
        } else {
            resp.put("gen", null);
        }

        if (d.insightsJson() != null) {
            var extra = new LinkedHashMap<String, Object>();
            extra.put("insights", readList(d.insightsJson()));
            extra.put("followups", readList(d.followupsJson()));
            extra.put("generatedAt",
                    d.extraAt() == null ? null : TS.format(d.extraAt().toLocalDateTime()));
            resp.put("extra", extra);
        } else {
            resp.put("extra", null);
        }

        var related = new ArrayList<Map<String, Object>>();
        for (var r : bankDao.relatedOf(id)) {
            related.add(Map.of("id", r.id(), "title", r.title(),
                    "categoryName", r.categoryName(), "score", r.score()));
        }
        resp.put("related", related);
        return resp;
    }

    @PutMapping("/questions/{id}/mastery")
    public Map<String, Object> setMastery(@PathVariable String id,
                                          @RequestBody Map<String, Integer> body) {
        Integer level = body.get("level");
        if (level == null || level < 0 || level > 2) {
            throw BizException.bad("level 取值必须为 0/1/2");
        }
        bankDao.setMastery(id, level);
        return Map.of("ok", true, "level", level);
    }

    /** 前端人工编辑三段式（source=manual，AI/规则批量不会覆盖） */
    @PutMapping("/questions/{id}/gen")
    public Map<String, Object> saveGen(@PathVariable String id, @RequestBody Map<String, String> body) {
        bankDao.findQuestion(id).orElseThrow(() -> BizException.bad("题目不存在: " + id));
        genDao.updateManual(id,
                body.getOrDefault("whatMd", ""),
                body.getOrDefault("whyMd", ""),
                body.getOrDefault("howMd", ""));
        return Map.of("ok", true);
    }

    /** 手动触发全库关联重算（一般由导入流程自动执行） */
    @PostMapping("/questions/{id}/recompute-related")
    public Map<String, Object> recomputeRelated(@PathVariable String id) {
        relatedService.recomputeAll();
        return Map.of("ok", true, "count", bankDao.relatedOf(id).size());
    }

    private List<Object> readList(String json) {
        try {
            return om.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 列表摘要：去 Markdown 标记，命中关键词时展示命中位置上下文 */
    static String snippet(String answerMd, String query) {
        if (answerMd == null || answerMd.isEmpty()) {
            return "";
        }
        String plain = stripMarkdown(answerMd);
        if (query != null && !query.isBlank()) {
            int idx = plain.toLowerCase().indexOf(query.strip().toLowerCase());
            if (idx >= 0) {
                int start = Math.max(0, idx - 40);
                int end = Math.min(plain.length(), idx + query.length() + 80);
                return (start > 0 ? "…" : "") + plain.substring(start, end)
                        + (end < plain.length() ? "…" : "");
            }
        }
        return plain.length() <= 110 ? plain : plain.substring(0, 110) + "…";
    }

    private static String stripMarkdown(String md) {
        String s = md.replaceAll("(?s)```.*?```", "〔代码〕");
        s = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("[#*>`|]", "").replaceAll("\\s+", " ").strip();
        return s;
    }
}
