package com.goocidata.agent.service;

import com.goocidata.agent.common.BizException;
import com.goocidata.agent.common.IdGen;
import com.goocidata.agent.entity.KbChunk;
import com.goocidata.agent.entity.KbDocument;
import com.goocidata.agent.entity.KnowledgeBase;
import com.goocidata.agent.mapper.KbChunkMapper;
import com.goocidata.agent.mapper.KbDocumentMapper;
import com.goocidata.agent.mapper.KnowledgeBaseMapper;
import com.goocidata.agent.util.ChunkSplitter;
import com.goocidata.agent.util.TextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库服务
 *
 * @author nzj
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private static final long MAX_FILE_SIZE = 30L * 1024 * 1024;
    private static final List<String> ALLOWED_EXT = List.of(
            "txt", "md", "markdown", "pdf", "docx", "csv", "log", "json", "sql", "java");

    private final KnowledgeBaseMapper kbMapper;
    private final KbDocumentMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final RetrievalService retrievalService;

    public List<KnowledgeBase> list() {
        return kbMapper.listAll();
    }

    public KnowledgeBase get(String id) {
        KnowledgeBase kb = kbMapper.findById(id);
        if (kb == null) {
            throw new BizException("知识库不存在");
        }
        return kb;
    }

    public KnowledgeBase save(KnowledgeBase input) {
        if (input.getName() == null || input.getName().trim().isEmpty()) {
            throw new BizException(400, "请填写知识库名称");
        }
        input.setName(input.getName().trim());
        if (input.getId() == null || input.getId().isEmpty()) {
            input.setId(IdGen.next());
            input.setDocCount(0);
            input.setChunkCount(0);
            input.setCreatedAt(IdGen.now());
            input.setUpdatedAt(input.getCreatedAt());
            kbMapper.insert(input);
        } else {
            get(input.getId());
            input.setUpdatedAt(IdGen.now());
            kbMapper.update(input);
        }
        return input;
    }

    @Transactional
    public void delete(String id) {
        get(id);
        chunkMapper.deleteByKb(id);
        docMapper.deleteByKb(id);
        kbMapper.delete(id);
        retrievalService.invalidate(id);
    }

    public List<KbDocument> listDocs(String kbId) {
        return docMapper.listByKb(kbId);
    }

    /**
     * 上传并解析文档
     */
    public KbDocument upload(String kbId, MultipartFile file) {
        get(kbId);
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要上传的文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BizException(400, "文件超过 30MB 限制");
        }
        String filename = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        String ext = extensionOf(filename);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(400, "暂不支持 ." + ext + " 格式，请上传 txt / md / pdf / docx / csv 等文本类文件");
        }

        String docId = IdGen.next();
        String text;
        try {
            text = TextExtractor.clean(TextExtractor.extract(file, ext));
        } catch (Exception e) {
            log.error("解析文档失败 name={}", filename, e);
            throw new BizException("文档解析失败：" + e.getMessage());
        }
        if (text == null || text.trim().isEmpty()) {
            throw new BizException("没有从文档中解析出文本内容。若是扫描版 PDF（图片），请先做 OCR 再上传");
        }

        List<String> pieces = ChunkSplitter.split(text, titleOf(filename));
        if (pieces.isEmpty()) {
            throw new BizException("文档内容为空，未生成任何知识切片");
        }

        String now = IdGen.now();
        insertChunks(kbId, docId, filename, pieces, now);

        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setName(filename);
        doc.setFileType(ext);
        doc.setFileSize(file.getSize());
        doc.setCharCount(text.length());
        doc.setChunkCount(pieces.size());
        doc.setStatus("READY");
        doc.setCreatedAt(now);
        docMapper.insert(doc);

        kbMapper.refreshStat(kbId, now);
        retrievalService.invalidate(kbId);
        log.info("文档入库完成 name={} chars={} chunks={}", filename, text.length(), pieces.size());
        return doc;
    }

    /**
     * 按当前切片策略重建某个文档的切片。
     * <p>
     * 原始文件不落盘，因此用现有切片顺序拼回原文；拼之前先剥掉上一版注入的
     * 「【文档 › 章节】」上下文行，保证多次重建不会让前缀层层叠加。
     */
    @Transactional
    public KbDocument reindex(String docId) {
        KbDocument doc = docMapper.findById(docId);
        if (doc == null) {
            throw new BizException("文档不存在");
        }
        List<KbChunk> old = chunkMapper.listByDoc(docId);
        if (old.isEmpty()) {
            throw new BizException("该文档没有切片，无法重建，请重新上传");
        }

        StringBuilder sb = new StringBuilder();
        for (KbChunk c : old) {
            String body = stripContextLine(c.getContent());
            if (body.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(body);
        }
        String text = sb.toString();
        if (text.trim().isEmpty()) {
            throw new BizException("重建失败：还原出的原文为空");
        }

        List<String> pieces = ChunkSplitter.split(text, titleOf(doc.getName()));
        if (pieces.isEmpty()) {
            throw new BizException("重建失败：切片结果为空");
        }

        chunkMapper.deleteByDoc(docId);
        insertChunks(doc.getKbId(), docId, doc.getName(), pieces, IdGen.now());
        docMapper.updateStat(docId, pieces.size(), doc.getCharCount() == null ? text.length() : doc.getCharCount());
        doc.setChunkCount(pieces.size());

        kbMapper.refreshStat(doc.getKbId(), IdGen.now());
        retrievalService.invalidate(doc.getKbId());
        log.info("切片重建完成 name={} 旧块={} 新块={}", doc.getName(), old.size(), pieces.size());
        return doc;
    }

    /**
     * 按知识库整体重建（逐个文档）
     */
    @Transactional
    public Map<String, Object> reindexKb(String kbId) {
        List<KbDocument> docs = docMapper.listByKb(kbId);
        int before = 0;
        int after = 0;
        for (KbDocument d : docs) {
            before += d.getChunkCount() == null ? 0 : d.getChunkCount();
            KbDocument nd = reindex(d.getId());
            after += nd.getChunkCount() == null ? 0 : nd.getChunkCount();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docs", docs.size());
        result.put("chunksBefore", before);
        result.put("chunksAfter", after);
        return result;
    }

    private void insertChunks(String kbId, String docId, String docName, List<String> pieces, String now) {
        List<KbChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            KbChunk chunk = new KbChunk();
            chunk.setId(IdGen.next());
            chunk.setKbId(kbId);
            chunk.setDocId(docId);
            chunk.setDocName(docName);
            chunk.setChunkIndex(i);
            chunk.setContent(pieces.get(i));
            chunk.setCharLen(pieces.get(i).length());
            chunk.setCreatedAt(now);
            chunks.add(chunk);
        }
        // 分批写入，避免单条 SQL 参数过多
        int batch = 200;
        for (int i = 0; i < chunks.size(); i += batch) {
            chunkMapper.batchInsert(chunks.subList(i, Math.min(i + batch, chunks.size())));
        }
    }

    /**
     * 去掉切片首行的「【文档 › 章节】」上下文标记
     */
    private String stripContextLine(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int idx = content.indexOf('\n');
        if (idx <= 0) {
            return content;
        }
        String first = content.substring(0, idx);
        if (first.startsWith("【") && first.endsWith("】")) {
            return content.substring(idx + 1);
        }
        return content;
    }

    /**
     * 文件名去扩展名，作为文档标题
     */
    private String titleOf(String filename) {
        if (filename == null) {
            return "";
        }
        int idx = filename.lastIndexOf('.');
        return idx <= 0 ? filename : filename.substring(0, idx);
    }

    @Transactional
    public void deleteDoc(String docId) {
        KbDocument doc = docMapper.findById(docId);
        if (doc == null) {
            return;
        }
        chunkMapper.deleteByDoc(docId);
        docMapper.delete(docId);
        kbMapper.refreshStat(doc.getKbId(), IdGen.now());
        retrievalService.invalidate(doc.getKbId());
    }

    public List<KbChunk> listChunks(String docId) {
        return chunkMapper.listByDoc(docId);
    }

    private String extensionOf(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) {
            throw new BizException(400, "文件缺少扩展名，无法判断格式");
        }
        return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }
}
