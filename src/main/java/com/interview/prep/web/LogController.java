package com.interview.prep.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行日志查询：从 data/logs/app.log 尾部读取，支持条数、级别、关键词过滤。
 */
@RestController
@RequestMapping("/api")
public class LogController {

    private static final Path LOG_FILE = Path.of("data/logs/app.log");

    @GetMapping("/logs")
    public Map<String, Object> logs(
            @RequestParam(defaultValue = "300") int lines,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String q) throws IOException {
        int safeLines = Math.min(Math.max(lines, 50), 2000);
        String levelToken = level == null || level.isBlank() ? null : " " + level.strip().toUpperCase() + " ";
        String keyword = q == null || q.isBlank() ? null : q.strip().toLowerCase();

        List<String> collected = new ArrayList<>();
        if (Files.exists(LOG_FILE)) {
            List<String> all = Files.readAllLines(LOG_FILE, StandardCharsets.UTF_8);
            for (int i = all.size() - 1; i >= 0 && collected.size() < safeLines; i--) {
                String l = all.get(i);
                if (levelToken != null && !l.contains(levelToken)) {
                    continue;
                }
                if (keyword != null && !l.toLowerCase().contains(keyword)) {
                    continue;
                }
                collected.add(l);
            }
        }
        Collections.reverse(collected);
        return Map.of("file", LOG_FILE.toString(), "total", collected.size(), "lines", collected);
    }
}
