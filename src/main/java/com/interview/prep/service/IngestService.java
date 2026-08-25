package com.interview.prep.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.interview.prep.dao.BankDao;
import com.interview.prep.md.IngestReport;
import com.interview.prep.md.MdParser;
import com.interview.prep.md.MdParser.ParsedCategory;
import com.interview.prep.md.MdParser.ParsedQuestion;

@Service
public class IngestService {

    private final MdParser parser;
    private final BankDao bankDao;
    private final RelatedService relatedService;

    @Value("${app.source-md}")
    private String sourceMd;

    public IngestService(MdParser parser, BankDao bankDao, RelatedService relatedService) {
        this.parser = parser;
        this.bankDao = bankDao;
        this.relatedService = relatedService;
    }

    /**
     * 全量解析 md 并增量入库：内容哈希未变的题目保留已生成的三段式与用户数据。
     */
    @Transactional
    public synchronized IngestReport ingest() throws IOException {
        List<ParsedCategory> categories = parser.parse(Path.of(sourceMd));

        int added = 0;
        int updated = 0;
        int unchanged = 0;
        Set<String> seenIds = new HashSet<>();

        for (ParsedCategory cat : categories) {
            bankDao.upsertCategory(cat.ord(), cat.name());
            for (ParsedQuestion q : cat.questions()) {
                seenIds.add(q.id());
                var meta = bankDao.findMeta(q.id());
                if (meta == null) {
                    bankDao.insertQuestion(q, cat.ord());
                    added++;
                } else if (!meta.contentHash().equals(q.contentHash())) {
                    bankDao.updateQuestionContent(q.id(), q.title(), q.answerMd(),
                            q.titleHash(), q.contentHash());
                    bankDao.invalidateGenerated(q.id()); // 内容变了，旧的三段式作废，由规则引擎重生成
                    updated++;
                } else if (!meta.titleHash().equals(q.titleHash())) {
                    bankDao.updateQuestionTitle(q.id(), q.title(), q.titleHash());
                    unchanged++; // 仅标题措辞变化，内容与生成结果仍有效
                } else {
                    unchanged++;
                }
            }
        }

        int removed = bankDao.deleteNotIn(seenIds);
        relatedService.recomputeAll();
        return new IngestReport(added, updated, removed, unchanged);
    }

    public String sourcePath() {
        return sourceMd;
    }

    /** 供设置页展示用 */
    public List<ParsedCategory> previewParse() throws IOException {
        return parser.parse(Path.of(sourceMd));
    }
}
