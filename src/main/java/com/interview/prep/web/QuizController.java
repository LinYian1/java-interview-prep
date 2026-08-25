package com.interview.prep.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.interview.prep.dao.BankDao;

/**
 * 抽题自测：先只发题目，用户自行回忆后在前端翻开答案并自评，
 * 自评结果直接联动掌握度（记住了→已掌握，没记住→模糊）。
 */
@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    private final BankDao bankDao;

    public QuizController(BankDao bankDao) {
        this.bankDao = bankDao;
    }

    @GetMapping("/draw")
    public Map<String, Object> draw(
            @RequestParam(defaultValue = "10") int count,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "0,1") String levels) {
        int safeCount = Math.min(Math.max(count, 1), 50);
        List<Integer> levelList = new ArrayList<>();
        for (String s : levels.split(",")) {
            try {
                int lv = Integer.parseInt(s.strip());
                if (lv >= 0 && lv <= 2) {
                    levelList.add(lv);
                }
            } catch (NumberFormatException ignored) {
                // 跳过非法片段
            }
        }
        if (levelList.isEmpty()) {
            levelList = List.of(0, 1);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (String id : bankDao.draw(levelList, categoryId, safeCount)) {
            bankDao.findQuestion(id).ifPresent(qa ->
                    items.add(Map.of("id", qa.id(), "title", qa.title())));
        }
        return Map.of("items", items);
    }

    /** body: {questionId, remembered} */
    @PostMapping("/judge")
    public Map<String, Object> judge(@RequestBody Map<String, Object> body) {
        String questionId = String.valueOf(body.get("questionId"));
        boolean remembered = Boolean.TRUE.equals(body.get("remembered"));
        bankDao.findQuestion(questionId)
                .orElseThrow(() -> BizException.bad("题目不存在: " + questionId));
        int level = remembered ? 2 : 1;
        bankDao.setMastery(questionId, level);
        return Map.of("ok", true, "level", level);
    }
}
