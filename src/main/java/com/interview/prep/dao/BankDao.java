package com.interview.prep.dao;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.interview.prep.md.MdParser.ParsedQuestion;

@Repository
public class BankDao {

    public record CategoryStat(long id, String name, int ord, int total, int mastered, int fuzzy) {}

    public record Meta(String titleHash, String contentHash) {}

    public record QaRow(String id, String title, String answerMd) {}

    public record RelRow(String id, int categoryId, String title, String answerMd) {}

    public record ListItem(String id, int num, String title, long categoryId,
                           String categoryName, int level) {}

    public record DetailRow(String id, int num, String title, String answerMd, long categoryId,
                            String categoryName, String whatMd, String whyMd, String howMd,
                            String source, String model, Timestamp generatedAt,
                            String insightsJson, String followupsJson, Timestamp extraAt,
                            int level) {}

    public record RelatedItem(String id, String title, String categoryName, double score) {}

    private final JdbcTemplate jdbc;

    public BankDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 分类 ----------

    public void upsertCategory(int id, String name) {
        jdbc.update("""
                INSERT INTO category(id, name, ord) VALUES(?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET name = excluded.name, ord = excluded.ord
                """, id, name, id);
    }

    public List<CategoryStat> categories() {
        return jdbc.query("""
                SELECT c.id, c.name, c.ord,
                       COUNT(q.id) AS total,
                       COALESCE(SUM(CASE WHEN m.level = 2 THEN 1 ELSE 0 END), 0) AS mastered,
                       COALESCE(SUM(CASE WHEN m.level = 1 THEN 1 ELSE 0 END), 0) AS fuzzy
                FROM category c
                LEFT JOIN question q ON q.category_id = c.id
                LEFT JOIN mastery m ON m.question_id = q.id
                GROUP BY c.id, c.name, c.ord
                ORDER BY c.ord
                """, (rs, i) -> new CategoryStat(rs.getLong(1), rs.getString(2), rs.getInt(3),
                rs.getInt(4), rs.getInt(5), rs.getInt(6)));
    }

    // ---------- 导入 ----------

    public Meta findMeta(String id) {
        var list = jdbc.query(
                "SELECT title_hash, content_hash FROM question WHERE id = ?",
                (rs, i) -> new Meta(rs.getString(1), rs.getString(2)), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public void insertQuestion(ParsedQuestion q, int categoryId) {
        jdbc.update("""
                INSERT INTO question(id, category_id, num, title, answer_md, title_hash, content_hash)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """, q.id(), categoryId, q.num(), q.title(), q.answerMd(), q.titleHash(), q.contentHash());
    }

    public void updateQuestionContent(String id, String title, String answerMd,
                                      String titleHash, String contentHash) {
        jdbc.update("""
                UPDATE question SET title = ?, answer_md = ?, title_hash = ?, content_hash = ?,
                       updated_at = datetime('now', 'localtime') WHERE id = ?
                """, title, answerMd, titleHash, contentHash, id);
    }

    public void updateQuestionTitle(String id, String title, String titleHash) {
        jdbc.update("UPDATE question SET title = ?, title_hash = ? WHERE id = ?", title, titleHash, id);
    }

    /** 内容变化后旧的生成结果全部作废（含人工编辑，因为其基于旧内容） */
    public void invalidateGenerated(String id) {
        jdbc.update("DELETE FROM gen_content WHERE question_id = ?", id);
    }

    public int deleteNotIn(Collection<String> keepIds) {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM question", Integer.class);
        if (keepIds.isEmpty()) {
            deleteAllCascade();
            return before == null ? 0 : before;
        }
        String in = String.join(",", Collections.nCopies(keepIds.size(), "?"));
        Object[] ids = keepIds.toArray();
        jdbc.update("DELETE FROM related WHERE question_id NOT IN (" + in + ") OR related_id NOT IN (" + in + ")",
                concat(ids, ids));
        for (String table : List.of("gen_content", "ai_extra", "mastery")) {
            jdbc.update("DELETE FROM " + table + " WHERE question_id NOT IN (" + in + ")", ids);
        }
        jdbc.update("DELETE FROM question WHERE id NOT IN (" + in + ")", ids);
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM question", Integer.class);
        return (before == null ? 0 : before) - (after == null ? 0 : after);
    }

    private void deleteAllCascade() {
        jdbc.update("DELETE FROM related");
        jdbc.update("DELETE FROM gen_content");
        jdbc.update("DELETE FROM ai_extra");
        jdbc.update("DELETE FROM mastery");
        jdbc.update("DELETE FROM question");
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] all = new Object[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    // ---------- 关联推荐 ----------

    public void updateKeywords(String id, String keywordsJson) {
        jdbc.update("UPDATE question SET keywords_json = ? WHERE id = ?", keywordsJson, id);
    }

    public List<RelRow> loadAllForRelated() {
        return jdbc.query(
                "SELECT id, category_id, title, answer_md FROM question",
                (rs, i) -> new RelRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4)));
    }

    public void replaceRelated(List<Object[]> rows) {
        jdbc.update("DELETE FROM related");
        jdbc.batchUpdate("INSERT OR REPLACE INTO related(question_id, related_id, score, ord) VALUES(?, ?, ?, ?)",
                rows);
    }

    public List<RelatedItem> relatedOf(String questionId) {
        return jdbc.query("""
                SELECT r.related_id, q.title, c.name, r.score
                FROM related r
                JOIN question q ON q.id = r.related_id
                JOIN category c ON c.id = q.category_id
                WHERE r.question_id = ?
                ORDER BY r.ord
                """, (rs, i) -> new RelatedItem(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getDouble(4)), questionId);
    }

    // ---------- 题目查询 ----------

    public List<QaRow> findWithoutGen() {
        return jdbc.query("""
                SELECT q.id, q.title, q.answer_md FROM question q
                LEFT JOIN gen_content g ON g.question_id = q.id
                WHERE g.question_id IS NULL
                ORDER BY q.category_id, q.num
                """, qaMapper());
    }

    public List<QaRow> findAllQuestions() {
        return jdbc.query("SELECT id, title, answer_md FROM question ORDER BY category_id, num", qaMapper());
    }

    public Optional<QaRow> findQuestion(String id) {
        var list = jdbc.query("SELECT id, title, answer_md FROM question WHERE id = ?", qaMapper(), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private static org.springframework.jdbc.core.RowMapper<QaRow> qaMapper() {
        return (rs, i) -> new QaRow(rs.getString(1), rs.getString(2), rs.getString(3));
    }

    public record SearchFilter(Long categoryId, Integer level, String like) {}

    public List<ListItem> search(SearchFilter f, int offset, int limit) {
        var sb = new StringBuilder("""
                SELECT q.id, q.num, q.title, q.category_id, c.name, COALESCE(m.level, 0) AS lv
                FROM question q
                JOIN category c ON c.id = q.category_id
                LEFT JOIN mastery m ON m.question_id = q.id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        appendFilters(sb, args, f);
        sb.append(" ORDER BY c.ord, q.num LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sb.toString(), (rs, i) -> new ListItem(rs.getString(1), rs.getInt(2),
                rs.getString(3), rs.getLong(4), rs.getString(5), rs.getInt(6)), args.toArray());
    }

    public int countSearch(SearchFilter f) {
        var sb = new StringBuilder("""
                SELECT COUNT(*) FROM question q
                LEFT JOIN mastery m ON m.question_id = q.id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        appendFilters(sb, args, f);
        Integer n = jdbc.queryForObject(sb.toString(), Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static void appendFilters(StringBuilder sb, List<Object> args, SearchFilter f) {
        if (f.categoryId() != null) {
            sb.append(" AND q.category_id = ?");
            args.add(f.categoryId());
        }
        if (f.level() != null) {
            sb.append(" AND COALESCE(m.level, 0) = ?");
            args.add(f.level());
        }
        if (f.like() != null && !f.like().isEmpty()) {
            sb.append(" AND (q.title LIKE ? ESCAPE '\\' OR q.answer_md LIKE ? ESCAPE '\\')");
            String p = "%" + f.like() + "%";
            args.add(p);
            args.add(p);
        }
    }

    /** LIKE 转义 % _ \ */
    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public DetailRow detail(String id) {
        var list = jdbc.query("""
                SELECT q.id, q.num, q.title, q.answer_md, q.category_id, c.name,
                       g.what_md, g.why_md, g.how_md, g.source, g.model, g.generated_at,
                       e.insights_json, e.followups_json, e.generated_at,
                       COALESCE(m.level, 0)
                FROM question q
                JOIN category c ON c.id = q.category_id
                LEFT JOIN gen_content g ON g.question_id = q.id
                LEFT JOIN ai_extra e ON e.question_id = q.id
                LEFT JOIN mastery m ON m.question_id = q.id
                WHERE q.id = ?
                """, (rs, i) -> new DetailRow(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getLong(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getString(11), rs.getTimestamp(12),
                rs.getString(13), rs.getString(14), rs.getTimestamp(15),
                rs.getInt(16)), id);
        return list.isEmpty() ? null : list.get(0);
    }

    // ---------- 掌握度与抽题 ----------

    public void setMastery(String questionId, int level) {
        jdbc.update("""
                INSERT INTO mastery(question_id, level, updated_at)
                VALUES(?, ?, datetime('now', 'localtime'))
                ON CONFLICT(question_id) DO UPDATE SET level = excluded.level,
                       updated_at = excluded.updated_at
                """, questionId, level);
    }

    public List<String> draw(List<Integer> levels, Long categoryId, int count) {
        var sb = new StringBuilder("""
                SELECT q.id FROM question q
                LEFT JOIN mastery m ON m.question_id = q.id
                WHERE COALESCE(m.level, 0) IN (
                """);
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < levels.size(); i++) {
            sb.append(i == 0 ? "?" : ", ?");
            args.add(levels.get(i));
        }
        sb.append(")");
        if (categoryId != null) {
            sb.append(" AND q.category_id = ?");
            args.add(categoryId);
        }
        sb.append(" ORDER BY RANDOM() LIMIT ?");
        args.add(count);
        return jdbc.query(sb.toString(), (rs, i) -> rs.getString(1), args.toArray());
    }

    public int totalQuestions() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM question", Integer.class);
        return n == null ? 0 : n;
    }

    /** 批量取答案原文（用于列表页摘要） */
    public Map<String, String> answersOf(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<String, String> map = new java.util.HashMap<>();
        jdbc.query("SELECT id, answer_md FROM question WHERE id IN (" + in + ")", rs -> {
            map.put(rs.getString(1), rs.getString(2));
        }, ids.toArray());
        return map;
    }
}
