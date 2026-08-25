package com.interview.prep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.interview.prep.md.IngestReport;
import com.interview.prep.service.IngestService;
import com.interview.prep.service.RuleEngineService;

@Component
public class Startup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Startup.class);

    private final JdbcTemplate jdbc;
    private final IngestService ingestService;
    private final RuleEngineService ruleEngineService;

    @Value("${app.source-md}")
    private String sourceMd;

    @Value("${app.ingest-on-startup}")
    private boolean ingestOnStartup;

    public Startup(JdbcTemplate jdbc, IngestService ingestService, RuleEngineService ruleEngineService) {
        this.jdbc = jdbc;
        this.ingestService = ingestService;
        this.ruleEngineService = ruleEngineService;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("PRAGMA journal_mode=WAL");
        if (ingestOnStartup) {
            try {
                IngestReport report = ingestService.ingest();
                log.info("题库导入完成：新增 {} 更新 {} 删除 {} 未变 {}",
                        report.added(), report.updated(), report.removed(), report.unchanged());
            } catch (Exception e) {
                log.error("题库导入失败，请检查 app.source-md 配置：{}", sourceMd, e);
            }
        }
        int filled = ruleEngineService.generateMissing();
        log.info("规则引擎补齐三段式：{} 题", filled);
    }
}
