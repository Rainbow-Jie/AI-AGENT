package com.goocidata.agent.controller;

import com.goocidata.agent.common.R;
import com.goocidata.agent.dto.ChunkHit;
import com.goocidata.agent.entity.KbChunk;
import com.goocidata.agent.entity.KbDocument;
import com.goocidata.agent.entity.KnowledgeBase;
import com.goocidata.agent.service.AgentService;
import com.goocidata.agent.service.KnowledgeService;
import com.goocidata.agent.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库接口（管理端）
 *
 * @author nzj
 */
@RestController
@RequestMapping("/api/admin/kb")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final RetrievalService retrievalService;

    @GetMapping
    public R<List<KnowledgeBase>> list() {
        return R.ok(knowledgeService.list());
    }

    @PostMapping("/save")
    public R<KnowledgeBase> save(@RequestBody KnowledgeBase input) {
        return R.ok(knowledgeService.save(input));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        knowledgeService.delete(id);
        return R.ok();
    }

    @GetMapping("/{id}/docs")
    public R<List<KbDocument>> docs(@PathVariable String id) {
        return R.ok(knowledgeService.listDocs(id));
    }

    @PostMapping("/{id}/upload")
    public R<KbDocument> upload(@PathVariable String id, @RequestPart("file") MultipartFile file) {
        return R.ok(knowledgeService.upload(id, file));
    }

    @DeleteMapping("/doc/{docId}")
    public R<Void> deleteDoc(@PathVariable String docId) {
        knowledgeService.deleteDoc(docId);
        return R.ok();
    }

    @GetMapping("/doc/{docId}/chunks")
    public R<List<KbChunk>> chunks(@PathVariable String docId) {
        return R.ok(knowledgeService.listChunks(docId));
    }

    /**
     * 按当前切片策略重建单个文档的切片
     */
    @PostMapping("/doc/{docId}/reindex")
    public R<KbDocument> reindexDoc(@PathVariable String docId) {
        return R.ok(knowledgeService.reindex(docId));
    }

    /**
     * 重建整个知识库的切片
     */
    @PostMapping("/{id}/reindex")
    public R<Map<String, Object>> reindexKb(@PathVariable String id) {
        return R.ok(knowledgeService.reindexKb(id));
    }

    /**
     * 检索测试：验证知识库切片质量
     */
    @PostMapping("/{id}/search")
    public R<Map<String, Object>> search(@PathVariable String id,
                                         @RequestParam String query,
                                         @RequestParam(defaultValue = "5") int topK) {
        List<ChunkHit> hits = retrievalService.retrieve(List.of(id), query, topK);
        Map<String, Object> data = new HashMap<>(2);
        data.put("query", query);
        data.put("hits", hits);
        return R.ok(data);
    }
}
