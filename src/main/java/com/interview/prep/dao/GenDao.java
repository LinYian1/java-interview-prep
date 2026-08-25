package com.interview.prep.dao;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GenDao {

    public record Content(String questionId, String whatMd, String whyMd, String howMd,
                          String source, String model, Timestamp generatedAt) {}

    public record Extra(String insightsJson, String followupsJson, Timestamp generatedAt) {}

    public record JobSnapshot(String type, String status, int total, int done, int failed,
                              String message) {}

    private final JdbcTemplate jdbc;

    public GenDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 三段式内容 ----------

    public void saveGenerated(String questionId, String whatMd, String whyMd, String howMd,
                              String source, String model) {
        jdbc.update("""
                INSERT INTO gen_content(question_id, what_md, why_md, how_md, source, model)
                VALUES(?, ?, ?, ?, ?, ?)
                ON CONFLICT(question_id) DO UPDATE SET
                    what_md = excluded.what_md, why_md = excluded.why_md, how_md = excluded.how_md,
                    source = excluded.source, model = excluded.model,
                    generated_at = datetime('now', 'localtime')
                """, questionId, whatMd, whyMd, howMd, source, model);
    }

    public Content findContent(String questionId) {
        var list = jdbc.query("""
                SELECT question_id, what_md, why_md, how_md, source, model, generated_at
                FROM gen_content WHERE question_id = ?
                """, (rs, i) -> new Content(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7)), questionId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateManual(String questionId, String whatMd, String whyMd, String howMd) {
        saveGenerated(questionId, whatMd, whyMd, howMd, "manual", null);
    }

    // ---------- AI 拓展 ----------

    public void saveExtra(String questionId, String insightsJson, String followupsJson) {
        jdbc.update("""
                INSERT INTO ai_extra(question_id, insights_json, followups_json)
                VALUES(?, ?, ?)
                ON CONFLICT(question_id) DO UPDATE SET
                    insights_json = excluded.insights_json, followups_json = excluded.followups_json,
                    generated_at = datetime('now', 'localtime')
                """, questionId, insightsJson, followupsJson);
    }

    public Extra findExtra(String questionId) {
        var list = jdbc.query(
                "SELECT insights_json, followups_json, generated_at FROM ai_extra WHERE question_id = ?",
                (rs, i) -> new Extra(rs.getString(1), rs.getString(2), rs.getTimestamp(3)), questionId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 需要生成三段式的题目：无记录或来源不是 ai（force 时全部） */
    public List<String> idsNeedingContent(boolean force) {
        String sql = """
                SELECT q.id FROM question q
                LEFT JOIN gen_content g ON g.question_id = q.id
                """ + (force
                ? "ORDER BY q.category_id, q.num"
                : "WHERE g.question_id IS NULL OR g.source <> 'ai' ORDER BY q.category_id, q.num");
        return jdbc.queryForList(sql, String.class);
    }

    public List<String> idsNeedingExtra(boolean force) {
        String sql = """
                SELECT q.id FROM question q
                LEFT JOIN ai_extra e ON e.question_id = q.id
                """ + (force
                ? "ORDER BY q.category_id, q.num"
                : "WHERE e.question_id IS NULL ORDER BY q.category_id, q.num");
        return jdbc.queryForList(sql, String.class);
    }

    // ---------- 批处理任务状态（持久化最近一次，供重启后查看） ----------

    public void saveJob(JobSnapshot s) {
        jdbc.update("""
                INSERT INTO job_state(id, type, status, total, done, failed, message)
                VALUES(1, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    type = excluded.type, status = excluded.status, total = excluded.total,
                    done = excluded.done, failed = excluded.failed, message = excluded.message,
                    updated_at = datetime('now', 'localtime')
                """, s.type(), s.status(), s.total(), s.done(), s.failed(), s.message());
    }

    public JobSnapshot loadJob() {
        var list = jdbc.query(
                "SELECT type, status, total, done, failed, message FROM job_state WHERE id = 1",
                (rs, i) -> new JobSnapshot(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5), rs.getString(6)));
        return list.isEmpty() ? null : list.get(0);
    }
}
