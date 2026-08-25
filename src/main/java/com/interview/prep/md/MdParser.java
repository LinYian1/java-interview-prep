package com.interview.prep.md;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 解析题库 Markdown：## 为分类，### 为题目，其余行归入当前题目的答案块。
 * 通过跟踪代码块围栏（```）避免把代码里的 # 误判为标题。
 */
@Component
public class MdParser {

    private static final Pattern QUESTION_NUM = Pattern.compile("^(\\d+)\\s*[.、．:：]?\\s*(.*)$");
    private static final Pattern CATEGORY_PREFIX = Pattern.compile("^[一二三四五六七八九十百]+\\s*、\\s*");
    private static final Pattern SEPARATOR = Pattern.compile("^-{3,}\\s*$");

    public record ParsedQuestion(String id, int num, String title, String answerMd,
                                 String titleHash, String contentHash) {}

    public record ParsedCategory(int ord, String name, List<ParsedQuestion> questions) {}

    public List<ParsedCategory> parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        List<ParsedCategory> categories = new ArrayList<>();
        CategoryBuilder cat = null;
        QuestionBuilder q = null;
        boolean fence = false;

        for (String line : lines) {
            String trimmed = line.strip();

            if (trimmed.startsWith("```")) {
                fence = !fence;
                if (q != null) {
                    q.lines.add(line);
                }
                continue;
            }
            if (fence) {
                if (q != null) {
                    q.lines.add(line);
                }
                continue;
            }

            if (trimmed.startsWith("###")) {
                if (cat != null) {
                    flushQuestion(categories, cat, q);
                    q = beginQuestion(cat, trimmed.substring(3).strip());
                }
            } else if (trimmed.startsWith("##")) {
                flushQuestion(categories, cat, q);
                q = null;
                flushCategory(categories, cat);
                String name = CATEGORY_PREFIX.matcher(trimmed.substring(2).strip()).replaceFirst("").strip();
                cat = new CategoryBuilder(categories.size() + 1, name);
            } else if (trimmed.startsWith("# ") || trimmed.equals("#")) {
                // 文档总标题，跳过
            } else if (SEPARATOR.matcher(trimmed).matches()) {
                // 题目间的分隔线，丢弃
            } else if (q != null) {
                q.lines.add(line);
            }
        }
        flushQuestion(categories, cat, q);
        flushCategory(categories, cat);
        return categories;
    }

    private QuestionBuilder beginQuestion(CategoryBuilder cat, String headingText) {
        var m = QUESTION_NUM.matcher(headingText);
        if (m.matches()) {
            return new QuestionBuilder(Integer.parseInt(m.group(1)), m.group(2).strip());
        }
        return new QuestionBuilder(cat.questions.size() + 1, headingText);
    }

    private void flushQuestion(List<ParsedCategory> categories, CategoryBuilder cat, QuestionBuilder q) {
        if (cat == null || q == null) {
            return;
        }
        String answer = String.join("\n", q.lines).strip();
        if (answer.isEmpty()) {
            return; // 空答案的孤立标题不收录
        }
        String id = cat.index + "-" + q.num;
        cat.questions.add(new ParsedQuestion(id, q.num, q.title, answer,
                sha256(q.title), sha256(answer)));
    }

    private void flushCategory(List<ParsedCategory> categories, CategoryBuilder cat) {
        if (cat != null && !cat.questions.isEmpty()) {
            categories.add(new ParsedCategory(cat.index, cat.name, cat.questions));
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class CategoryBuilder {
        final int index;
        final String name;
        final List<ParsedQuestion> questions = new ArrayList<>();

        CategoryBuilder(int index, String name) {
            this.index = index;
            this.name = name;
        }
    }

    private static final class QuestionBuilder {
        final int num;
        final String title;
        final List<String> lines = new ArrayList<>();

        QuestionBuilder(int num, String title) {
            this.num = num;
            this.title = title;
        }
    }
}
