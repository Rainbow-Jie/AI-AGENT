package com.goocidata.agent.service;

import com.goocidata.agent.dto.ChunkHit;
import com.goocidata.agent.entity.KbChunk;
import com.goocidata.agent.mapper.KbChunkMapper;
import com.goocidata.agent.util.Bm25;
import com.goocidata.agent.util.TextTokenizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 知识库检索：BM25 + 内存索引缓存
 *
 * @author nzj
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final int MAX_CHUNKS = 20000;
    private static final int MAX_CACHE_ENTRIES = 32;
    /** 概览型问题（问清单/分类/推荐）需要跨章节多取几块，否则答案只能覆盖知识库的一小部分 */
    private static final int OVERVIEW_MAX_TOPK = 12;
    private static final Pattern OVERVIEW_HINT = Pattern.compile(
            "哪些|哪种|什么|有啥|都有|推荐|清单|列表|大全|几类|分类|全部|汇总|怎么选|能不能喝|可以喝|喝什么");

    private final KbChunkMapper chunkMapper;
    private final Map<String, KbIndex> indexCache = new ConcurrentHashMap<>();

    /**
     * 检索
     *
     * @param kbIds 知识库 ID 列表
     * @param query 用户问题
     * @param topK  取前 K 条
     */
    public List<ChunkHit> retrieve(List<String> kbIds, String query, int topK) {
        if (kbIds == null || kbIds.isEmpty() || query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(kbIds));
        Collections.sort(ids);
        String cacheKey = String.join(",", ids);

        KbIndex index = indexCache.computeIfAbsent(cacheKey, k -> buildIndex(ids));
        if (index.chunks.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> queryTokens = Bm25.queryTokens(query);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChunkHit> hits = new ArrayList<>();
        for (int i = 0; i < index.chunks.size(); i++) {
            List<String> tokens = index.tokens.get(i);
            if (tokens.isEmpty()) {
                continue;
            }
            double score = index.bm25.score(queryTokens, tokens, tokens.size());
            if (score <= 0) {
                continue;
            }
            KbChunk c = index.chunks.get(i);
            ChunkHit hit = new ChunkHit();
            hit.setChunkId(c.getId());
            hit.setDocId(c.getDocId());
            hit.setDocName(c.getDocName());
            hit.setChunkIndex(c.getChunkIndex());
            hit.setContent(c.getContent());
            hit.setScore(Math.round(score * 1000D) / 1000D);
            hit.setPreview(preview(c.getContent()));
            hits.add(hit);
        }

        hits.sort(Comparator.comparing(ChunkHit::getScore).reversed());

        // 概览型问题放宽取样范围：用户问「推荐哪些 / 有什么 / 分类」时，
        // 只取 4 条会让答案只覆盖知识库的一两个章节，其余类别被说成「没有数据」。
        boolean overview = OVERVIEW_HINT.matcher(query).find();
        int effectiveTopK = Math.max(1, topK);
        if (overview) {
            effectiveTopK = Math.min(Math.max(topK * 3, topK + 4), OVERVIEW_MAX_TOPK);
        }
        // 同一文档的配额：概览型问题要跨章节取全，不再按文档卡脖子；
        // 精确问答仍保留上限，避免单一文档把上下文吃光。
        int perDocCap = overview ? effectiveTopK : Math.max(3, (int) Math.ceil(effectiveTopK * 0.75D));

        // 概览型问题按「章节」轮询：同一章节先各取一条，保证类别覆盖；
        // 名额没填满再回头补同章节的次优块。
        List<ChunkHit> ordered;
        if (overview) {
            ordered = new ArrayList<>(hits.size());
            List<ChunkHit> spare = new ArrayList<>();
            Set<String> seenSections = new HashSet<>();
            for (ChunkHit h : hits) {
                String sec = sectionOf(h.getContent());
                if (sec.isEmpty() || seenSections.add(sec)) {
                    ordered.add(h);
                } else {
                    spare.add(h);
                }
            }
            ordered.addAll(spare);
        } else {
            ordered = hits;
        }

        List<ChunkHit> result = new ArrayList<>();
        Map<String, Integer> perDoc = new ConcurrentHashMap<>();
        for (ChunkHit hit : ordered) {
            int used = perDoc.getOrDefault(hit.getDocId(), 0);
            if (used >= perDocCap) {
                continue;
            }
            perDoc.put(hit.getDocId(), used + 1);
            result.add(hit);
            if (result.size() >= effectiveTopK) {
                break;
            }
        }
        if (overview) {
            log.info("概览型检索 query={} topK={} -> {} 命中 {}", query, topK, effectiveTopK, result.size());
        }
        return result;
    }

    /**
     * 取切片首行的「【文档 › 章节】」标记，用于概览时的章节去重
     */
    private String sectionOf(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int idx = content.indexOf('\n');
        String first = idx < 0 ? content : content.substring(0, idx);
        return first.startsWith("【") && first.endsWith("】") ? first : "";
    }

    /**
     * 知识库内容变更后清理缓存
     */
    public void invalidate(String kbId) {
        indexCache.keySet().removeIf(key -> key.contains(kbId));
    }

    public void clearAll() {
        indexCache.clear();
    }

    private KbIndex buildIndex(List<String> kbIds) {
        if (indexCache.size() > MAX_CACHE_ENTRIES) {
            indexCache.clear();
        }
        long start = System.currentTimeMillis();
        List<KbChunk> chunks = chunkMapper.listByKbIds(kbIds);
        if (chunks.size() > MAX_CHUNKS) {
            chunks = chunks.subList(0, MAX_CHUNKS);
        }
        List<List<String>> tokens = new ArrayList<>(chunks.size());
        Bm25.Builder builder = Bm25.builder();
        for (KbChunk chunk : chunks) {
            List<String> tk = tokenizeWithDup(chunk.getContent());
            tokens.add(tk);
            Set<String> unique = new HashSet<>(tk);
            builder.addDoc(new ArrayList<>(unique), tk.size());
        }
        KbIndex index = new KbIndex();
        index.chunks = chunks;
        index.tokens = tokens;
        index.bm25 = builder.build();
        log.info("构建知识库索引 kb={} chunks={} 耗时 {}ms", kbIds, chunks.size(),
                System.currentTimeMillis() - start);
        return index;
    }

    /**
     * 带重复的词项列表（保留词频用于 BM25）
     */
    private List<String> tokenizeWithDup(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        String normalized = TextTokenizer.normalize(text);
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (isCjk(c)) {
                flushWordDup(word, result);
                cjk.append(c);
            } else if (isAlnum(c)) {
                flushCjkDup(cjk, result);
                word.append(c);
            } else {
                flushCjkDup(cjk, result);
                flushWordDup(word, result);
            }
        }
        flushCjkDup(cjk, result);
        flushWordDup(word, result);
        return result;
    }

    private void flushCjkDup(StringBuilder buf, List<String> out) {
        int len = buf.length();
        if (len == 0) {
            return;
        }
        if (len == 1) {
            out.add(buf.toString());
        } else {
            for (int i = 0; i < len - 1; i++) {
                out.add(buf.substring(i, i + 2));
            }
        }
        buf.setLength(0);
    }

    private void flushWordDup(StringBuilder buf, List<String> out) {
        if (buf.length() > 0) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }

    private boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    private boolean isAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
    }

    private String preview(String content) {
        if (content == null) {
            return "";
        }
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }

    private static final class KbIndex {
        private List<KbChunk> chunks;
        private List<List<String>> tokens;
        private Bm25 bm25;
    }
}
