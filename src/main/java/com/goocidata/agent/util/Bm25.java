package com.goocidata.agent.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 打分器（k1=1.5, b=0.75）
 *
 * @author nzj
 */
public final class Bm25 {

    private static final double K1 = 1.5D;
    private static final double B = 0.75D;

    private final double avgDocLen;
    private final int totalDocs;
    private final Map<String, Integer> docFreq;

    public Bm25(double avgDocLen, int totalDocs, Map<String, Integer> docFreq) {
        this.avgDocLen = avgDocLen <= 0 ? 1 : avgDocLen;
        this.totalDocs = totalDocs;
        this.docFreq = docFreq;
    }

    /**
     * 对单个文档打分
     *
     * @param queryTokens  查询词项（可重复，重复即加权）
     * @param docTokens    文档词项（含重复，用于算 tf）
     * @param docLen       文档长度（词项数）
     */
    public double score(List<String> queryTokens, List<String> docTokens, int docLen) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : docTokens) {
            tf.merge(t, 1, Integer::sum);
        }
        double score = 0D;
        for (String q : queryTokens) {
            Integer f = tf.get(q);
            if (f == null) {
                continue;
            }
            int df = docFreq.getOrDefault(q, 0);
            double idf = Math.log(1 + (totalDocs - df + 0.5D) / (df + 0.5D));
            double numerator = f * (K1 + 1);
            double denominator = f + K1 * (1 - B + B * docLen / avgDocLen);
            score += idf * numerator / denominator;
        }
        return score;
    }

    /**
     * 构建打分所需的全局统计
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private double totalLen;
        private int totalDocs;
        private final Map<String, Integer> df = new HashMap<>();

        public Builder addDoc(List<String> uniqueTokens, int docLen) {
            totalDocs++;
            totalLen += docLen;
            for (String t : uniqueTokens) {
                df.merge(t, 1, Integer::sum);
            }
            return this;
        }

        public Bm25 build() {
            double avg = totalDocs == 0 ? 1 : totalLen / totalDocs;
            return new Bm25(avg, totalDocs, Collections.unmodifiableMap(new HashMap<>(df)));
        }
    }

    /**
     * 生成查询词项列表（保留重复以强化关键词权重）
     */
    public static List<String> queryTokens(String query) {
        return new ArrayList<>(TextTokenizer.tokenize(query));
    }
}
