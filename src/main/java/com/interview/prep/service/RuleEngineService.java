package com.interview.prep.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.interview.prep.dao.BankDao;
import com.interview.prep.dao.GenDao;

/**
 * 规则引擎 v3。针对 v2 的两个问题做了细化：
 * 1. 概念内容里出现「因为/防止」等词就被误分进为什么 —— 现在 WHY/HOW 必须有强信号词支持，
 *    仅弱信号（软词）不足以改变归属，默认引力回到「是什么」；
 * 2. 完整的概念列表被逐条拆得零碎 —— 连续的同级列表条目视为一个整体，整段共占一桶，
 *    仅当某条自身有相反的强证据时才允许脱离。
 * 另保留：标题先验、首尾块锚点、代码块恒入怎么做。
 */
@Service
public class RuleEngineService {

    private static final String[] WHY_STRONG = {
            "因为", "原因", "为什么", "目的是", "目的在于", "为了实现", "底层实现", "底层原理",
            "工作原理", "实现原理", "原理是", "机制是", "设计动机", "之所以" };
    private static final String[] WHY_SOFT = {
            "为了", "好处", "优点", "优势", "意义", "从而", "进而", "使得", "避免", "防止",
            "保证", "确保", "提升", "减少", "导致", "作用" };
    private static final String[] HOW_STRONG = {
            "如何", "怎么", "怎样", "步骤", "流程", "示例", "代码", "配置", "编写", "命令",
            "语法", "写法", "手写", "案例", "实践", "调用", "演示", "使用方法", "使用方式",
            "创建", "实现步骤" };
    private static final String[] WHAT_STRONG = {
            "定义", "是指", "指的是", "所谓", "全称", "缩写", "属于", "答案是", "等于", "区别",
            "不同点", "差异", "对比", "vs", "分别", "特点", "特性", "分类", "种类", "包括",
            "包含", "核心", "汇总", "概念" };
    private static final String[] WHAT_SOFT = {
            "含义", "内容", "要点", "总结", "如下", "说明", "介绍" };

    private static final Pattern TITLE_WHY = Pattern.compile("为什么|原因|原理|底层|机制|为何|意义");
    private static final Pattern TITLE_HOW =
            Pattern.compile("如何|怎么|怎样|哪些方式|几种方式|步骤|手写|写出|创建线程池|编写|实现一个");
    private static final Pattern TOP_BULLET =
            Pattern.compile("^(?:[-*+]|\\d+[.、)])\\s+.*");

    public record GenDraft(String what, String why, String how) {}

    private record Seg(String text, boolean code, boolean bullet) {}

    /** w/y/h 为综合得分，sw/sy/sh 为强信号命中数（用于门槛判断） */
    private record Score(double w, double y, double h, int sw, int sy, int sh) {}

    private enum Bucket { WHAT, WHY, HOW }

    private final BankDao bankDao;
    private final GenDao genDao;

    public RuleEngineService(BankDao bankDao, GenDao genDao) {
        this.bankDao = bankDao;
        this.genDao = genDao;
    }

    @Transactional
    public int generateMissing() {
        int count = 0;
        for (var q : bankDao.findWithoutGen()) {
            GenDraft d = draft(q.title(), q.answerMd());
            genDao.saveGenerated(q.id(), d.what(), d.why(), d.how(), "rule", null);
            count++;
        }
        return count;
    }

    /** 单题重生成（覆盖 rule/ai 结果，manual 由前端二次确认后再调） */
    @Transactional
    public void regenerateOne(String questionId) {
        var q = bankDao.findQuestion(questionId)
                .orElseThrow(() -> new IllegalArgumentException("题目不存在: " + questionId));
        GenDraft d = draft(q.title(), q.answerMd());
        genDao.saveGenerated(questionId, d.what(), d.why(), d.how(), "rule", null);
    }

    /** 批量重生成所有 rule 来源或缺失的题目，不覆盖 ai/manual */
    @Transactional
    public int regenerateRuleOnly() {
        int count = 0;
        for (var q : bankDao.findAllQuestions()) {
            var existing = genDao.findContent(q.id());
            if (existing == null || "rule".equals(existing.source())) {
                GenDraft d = draft(q.title(), q.answerMd());
                genDao.saveGenerated(q.id(), d.what(), d.why(), d.how(), "rule", null);
                count++;
            }
        }
        return count;
    }

    public GenDraft draft(String title, String answerMd) {
        int prior = titlePrior(title); // 0=WHAT 1=WHY 2=HOW
        List<Seg> segs = split(answerMd);
        int n = segs.size();

        Score[] scores = new Score[n];
        for (int i = 0; i < n; i++) {
            Seg seg = segs.get(i);
            if (seg.code()) {
                scores[i] = new Score(0, 0, 100, 0, 0, 1); // 代码块恒为 HOW
                continue;
            }
            String low = seg.text().toLowerCase();
            int ws = hits(low, WHAT_STRONG);
            int ys = hits(low, WHY_STRONG);
            int hs = hits(low, HOW_STRONG);
            double w = ws * 2.0 + hits(low, WHAT_SOFT) * 0.5;
            double y = ys * 2.0 + hits(low, WHY_SOFT) * 0.5;
            double h = hs * 2.0;

            if (i == 0) {
                w += 1.5; // 开头段通常是定义/总起
            }
            if (i == n - 1 && n > 2) {
                w += 0.5; // 结尾段常是小结
            }
            String stripped = seg.text().strip();
            if (stripped.startsWith("**")) {
                w += 0.75; // 加粗术语开头的条目多为概念陈述
            }
            if (stripped.startsWith("|")) {
                w += 1; // 表格多为参数/对比说明
            }
            switch (prior) {
                case 1 -> y += 1.5;
                case 2 -> h += 1.5;
                default -> w += 0.5;
            }
            scores[i] = new Score(w, y, h, ws, ys, hs);
        }

        Bucket[] out = assign(segs, scores, prior);

        List<String> what = new ArrayList<>();
        List<String> why = new ArrayList<>();
        List<String> how = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            switch (out[i]) {
                case WHY -> why.add(segs.get(i).text());
                case HOW -> how.add(segs.get(i).text());
                default -> what.add(segs.get(i).text());
            }
        }

        if (what.isEmpty() && !how.isEmpty()) {
            what.add(how.remove(0));
        }
        String whatMd = String.join("\n\n", what);
        String whyMd = why.isEmpty()
                ? "> 原答案未直接展开“为什么”。建议结合上面的结论追问自己：这个设计的动机是什么？解决了什么问题？"
                : String.join("\n\n", why);
        String howMd = how.isEmpty()
                ? "> 本题偏概念辨析，没有固定操作步骤。建议记忆时为每个要点配一个使用场景或例子。"
                : String.join("\n\n", how);
        return new GenDraft(whatMd, whyMd, howMd);
    }

    /** 主分类：连续同级列表条目成段共桶，其余单独判定 */
    private static Bucket[] assign(List<Seg> segs, Score[] scores, int prior) {
        int n = segs.size();
        Bucket[] out = new Bucket[n];
        int i = 0;
        while (i < n) {
            if (segs.get(i).code()) {
                out[i] = Bucket.HOW;
                i++;
                continue;
            }
            int j = i;
            while (j < n && !segs.get(j).code() && segs.get(j).bullet()) {
                j++;
            }
            if (j - i >= 2) {
                Bucket winner = decide(sum(scores, i, j), prior);
                for (int k = i; k < j; k++) {
                    out[k] = mayDetach(scores[k], winner, prior);
                }
            } else {
                out[i] = decide(scores[i], prior);
            }
            i = Math.max(j, i + 1);
        }
        return out;
    }

    private static Score sum(Score[] scores, int from, int to) {
        double w = 0, y = 0, h = 0;
        int sw = 0, sy = 0, sh = 0;
        for (int k = from; k < to; k++) {
            Score s = scores[k];
            w += s.w();
            y += s.y();
            h += s.h();
            sw += s.sw();
            sy += s.sy();
            sh += s.sh();
        }
        return new Score(w, y, h, sw, sy, sh);
    }

    /**
     * 判定单个块归属。关键规则：WHY/HOW 只有在拥有强信号（或标题先验支持）时才可能胜出，
     * 否则其得分大幅折减——避免「因为」「防止」这类软词把概念内容拖进为什么。
     */
    private static Bucket decide(Score s, int prior) {
        double eW = s.w();
        double eY = (prior == 1 || s.sy() >= 1) ? s.y() : s.y() * 0.3;
        double eH = (prior == 2 || s.sh() >= 1) ? s.h() : s.h() * 0.25;
        if (eW >= eY && eW >= eH) {
            return Bucket.WHAT;
        }
        return eY >= eH ? Bucket.WHY : Bucket.HOW;
    }

    /** 段内条目脱离条件：自身有相反方向的强信号，且得分明显更高 */
    private static Bucket mayDetach(Score s, Bucket runWinner, int prior) {
        Bucket own = decide(s, prior);
        if (own == runWinner) {
            return runWinner;
        }
        boolean strongSupport = switch (own) {
            case WHY -> s.sy() >= 1 || prior == 1;
            case HOW -> s.sh() >= 1 || prior == 2;
            default -> true;
        };
        double ownScore = scoreOf(s, own, prior);
        double winnerScore = scoreOf(s, runWinner, prior);
        if (strongSupport && ownScore > winnerScore + 0.5) {
            return own;
        }
        return runWinner;
    }

    private static double scoreOf(Score s, Bucket b, int prior) {
        return switch (b) {
            case WHY -> (prior == 1 || s.sy() >= 1) ? s.y() : s.y() * 0.3;
            case HOW -> (prior == 2 || s.sh() >= 1) ? s.h() : s.h() * 0.25;
            default -> s.w();
        };
    }

    private static int titlePrior(String title) {
        if (TITLE_HOW.matcher(title).find()) {
            return 2;
        }
        if (TITLE_WHY.matcher(title).find()) {
            return 1;
        }
        return 0;
    }

    private static int hits(String lowerText, String[] cues) {
        int n = 0;
        for (String cue : cues) {
            int idx = 0;
            while ((idx = lowerText.indexOf(cue, idx)) >= 0) {
                n++;
                idx += cue.length();
            }
        }
        return n;
    }

    /**
     * 两阶段切分：先按代码围栏和空行分组，再把文本组按顶层列表条目炸开；
     * bullet 标记标识「顶层列表条目」，供成段共桶逻辑使用。
     */
    private static List<Seg> split(String md) {
        List<Object> raw = new ArrayList<>();
        List<String> textBuf = new ArrayList<>();
        boolean fence = false;
        StringBuilder codeBuf = null;

        for (String line : md.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                if (fence) {
                    codeBuf.append(line).append('\n');
                    raw.add(new Seg(codeBuf.toString().strip(), true, false));
                    codeBuf = null;
                    fence = false;
                } else {
                    flushText(raw, textBuf);
                    codeBuf = new StringBuilder(line).append('\n');
                    fence = true;
                }
                continue;
            }
            if (fence) {
                codeBuf.append(line).append('\n');
            } else if (line.isBlank()) {
                flushText(raw, textBuf);
            } else {
                textBuf.add(line);
            }
        }
        if (fence && codeBuf != null) {
            raw.add(new Seg(codeBuf.toString().strip(), true, false));
        }
        flushText(raw, textBuf);

        List<Seg> out = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof Seg s) {
                out.add(s);
            } else {
                @SuppressWarnings("unchecked")
                List<Seg> units = explodeBullets((List<String>) o);
                out.addAll(units);
            }
        }
        return out;
    }

    /** 把文本块按顶层列表条目拆开；缩进行与续行跟随所属条目 */
    private static List<Seg> explodeBullets(List<String> lines) {
        List<Seg> units = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String line : lines) {
            boolean topLevelBullet = !line.startsWith(" ") && !line.startsWith("\t")
                    && TOP_BULLET.matcher(line).matches();
            if (topLevelBullet && !cur.isEmpty()) {
                units.add(new Seg(String.join("\n", cur), false, true));
                cur = new ArrayList<>();
            }
            cur.add(line);
        }
        if (!cur.isEmpty()) {
            boolean firstIsBullet = !lines.get(0).startsWith(" ")
                    && TOP_BULLET.matcher(lines.get(0)).matches();
            units.add(new Seg(String.join("\n", cur), false, firstIsBullet));
        }
        return units;
    }

    @SuppressWarnings("unchecked")
    private static void flushText(List<Object> blocks, List<String> buf) {
        if (!buf.isEmpty()) {
            blocks.add(new ArrayList<>(buf)); // 占位，稍后在 split 中转 explodeBullets
            buf.clear();
        }
    }
}
