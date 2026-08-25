package com.interview.prep.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.prep.dao.BankDao;

/**
 * 库内关联推荐：英文技术 token + 标题中文二元组作为关键词，
 * 高频词剪枝后按余弦相似度计算题目相关性，每题保留 Top8。
 */
@Service
public class RelatedService {

    private static final Logger log = LoggerFactory.getLogger(RelatedService.class);

    private static final Set<String> EN_STOP = Set.of(
            "the", "a", "an", "of", "in", "on", "for", "to", "and", "or", "is", "are", "was",
            "were", "be", "been", "what", "how", "why", "when", "which", "that", "this", "it",
            "its", "as", "by", "with", "can", "could", "will", "would", "not", "no", "do",
            "does", "did", "java", "use", "used", "using", "there", "have", "has", "if", "at",
            "from", "into", "than", "then", "so", "such", "some", "any", "each", "between",
            "about", "out", "up", "new", "get", "set", "yes", "true", "false", "example");

    private static final Set<String> BIGRAM_STOP = Set.of(
            "什么", "哪些", "如何", "怎么", "区别", "有哪", "几种", "简述", "说说", "以及",
            "使用", "方法", "方式", "步骤", "说明", "概念", "含义", "介绍", "一下", "问题",
            "面试", "相关", "分别", "之间", "是不是", "两个");

    private static final String PARTICLES = "的了在和是与对中为等及或被把从向于其此各该就都很都还又再最较更非常不没";

    private static final int TOP_RELATED = 8;
    private static final double MIN_SCORE = 0.08;

    private final BankDao bankDao;
    private final ObjectMapper om;

    public RelatedService(BankDao bankDao, ObjectMapper om) {
        this.bankDao = bankDao;
        this.om = om;
    }

    private record Doc(String id, int categoryId, String title, String answerMd) {}

    @Transactional
    public void recomputeAll() {
        List<Doc> docs = bankDao.loadAllForRelated().stream()
                .map(r -> new Doc(r.id(), r.categoryId(), r.title(), r.answerMd()))
                .toList();
        int n = docs.size();
        if (n == 0) {
            return;
        }

        List<Map<String, Double>> tfs = new ArrayList<>(n);
        Map<String, Integer> df = new HashMap<>();
        for (Doc d : docs) {
            Map<String, Double> tf = rawTermFreq(d.title(), d.answerMd());
            tfs.add(tf);
            for (String term : tf.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }

        int dfCap = Math.max(3, (int) (n * 0.25));
        List<Map<String, Double>> weights = new ArrayList<>(n);
        for (Map<String, Double> tf : tfs) {
            Map<String, Double> w = new HashMap<>();
            tf.forEach((term, freq) -> {
                if (df.getOrDefault(term, 0) <= dfCap) {
                    w.put(term, 1.0 + Math.log(freq));
                }
            });
            weights.add(w);
        }

        for (int i = 0; i < n; i++) {
            String json = toJson(topTerms(weights.get(i), 15));
            bankDao.updateKeywords(docs.get(i).id(), json);
        }

        List<Object[]> rows = new ArrayList<>();
        Map<String, List<Object[]>> byQuestion = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double score = cosine(weights.get(i), weights.get(j));
                if (score < MIN_SCORE) {
                    continue;
                }
                boolean sameCat = docs.get(i).categoryId() == docs.get(j).categoryId();
                double s = sameCat ? score * 1.15 : score;
                rows.add(new Object[]{docs.get(i).id(), docs.get(j).id(), s});
                rows.add(new Object[]{docs.get(j).id(), docs.get(i).id(), s});
            }
        }
        for (Object[] r : rows) {
            byQuestion.computeIfAbsent((String) r[0], k -> new ArrayList<>()).add(r);
        }
        List<Object[]> inserts = new ArrayList<>();
        for (var e : byQuestion.entrySet()) {
            e.getValue().sort((a, b) -> Double.compare((double) b[2], (double) a[2]));
            int ord = 0;
            for (Object[] r : e.getValue()) {
                if (ord >= TOP_RELATED) {
                    break;
                }
                inserts.add(new Object[]{r[0], r[1], r[2], ord++});
            }
        }
        bankDao.replaceRelated(inserts);
        log.info("关联推荐重算完成：{} 题，{} 条关联", n, inserts.size());
    }

    private static Map<String, Double> rawTermFreq(String title, String answer) {
        Map<String, Double> tf = new HashMap<>();
        var en = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]{1,}").matcher(title);
        while (en.find()) {
            String t = en.group().toLowerCase();
            if (!EN_STOP.contains(t)) {
                tf.merge(t, 3.0, Double::sum); // 标题权重 x3
            }
        }
        en = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]{1,}").matcher(answer);
        while (en.find()) {
            String t = en.group().toLowerCase();
            if (!EN_STOP.contains(t)) {
                tf.merge(t, 1.0, Double::sum);
            }
        }
        // 标题中文二元组，过滤含虚词的
        String t = title;
        for (int i = 0; i < t.length() - 1; i++) {
            char c1 = t.charAt(i), c2 = t.charAt(i + 1);
            if (isCjk(c1) && isCjk(c2) && !PARTICLES.contains(String.valueOf(c1))
                    && !PARTICLES.contains(String.valueOf(c2))) {
                String bigram = t.substring(i, i + 2);
                if (!BIGRAM_STOP.contains(bigram)) {
                    tf.merge(bigram, 3.0, Double::sum);
                }
            }
        }
        return tf;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Map<String, Double> small = a.size() <= b.size() ? a : b;
        Map<String, Double> big = small == a ? b : a;
        double dot = 0;
        for (var e : small.entrySet()) {
            Double other = big.get(e.getKey());
            if (other != null) {
                dot += e.getValue() * other;
            }
        }
        if (dot == 0) {
            return 0;
        }
        double na = norm(a), nb = norm(b);
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static double norm(Map<String, Double> m) {
        double s = 0;
        for (double v : m.values()) {
            s += v * v;
        }
        return s;
    }

    private List<Map.Entry<String, Double>> topTerms(Map<String, Double> w, int limit) {
        return w.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(limit)
                .toList();
    }

    private String toJson(List<Map.Entry<String, Double>> terms) {
        try {
            return om.writeValueAsString(terms.stream().map(Map.Entry::getKey).toList());
        } catch (Exception e) {
            return "[]";
        }
    }
}
