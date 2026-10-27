package com.goocidata.agent.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 轻量分词器：中文按字符 bigram、英文数字按词，兼顾召回与零依赖。
 * <p>
 * 说明：不使用词典分词，避免引入额外依赖与词典维护成本。
 * 中文 bigram 对「查准」略有损失，但对知识库召回足够，且对未登录词（产品型号、编号）更友好。
 *
 * @author nzj
 */
public final class TextTokenizer {

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "的", "了", "和", "是", "在", "我", "有", "就", "不", "人", "都", "一", "一个", "上", "也",
            "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这", "那",
            "什么", "怎么", "如何", "为什么", "可以", "需要", "以及", "但是", "然后", "因为", "所以",
            "如果", "并且", "或者", "而且", "还有", "我们", "他们", "这个", "那个", "哪些", "多少",
            "the", "a", "an", "is", "are", "was", "were", "of", "to", "in", "on", "at", "for", "and",
            "or", "it", "this", "that", "with", "as", "by", "be", "from", "will", "can", "not", "do"
    ));

    private TextTokenizer() {
    }

    /**
     * 分词，返回去重后的词项集合（用于建索引 / 打分）
     */
    public static List<String> tokenize(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        String normalized = normalize(text);
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();

        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (isCjk(c)) {
                flushWord(word, result);
                cjk.append(c);
            } else if (isAlnum(c)) {
                flushCjk(cjk, result);
                word.append(c);
            } else {
                flushCjk(cjk, result);
                flushWord(word, result);
            }
        }
        flushCjk(cjk, result);
        flushWord(word, result);
        return new ArrayList<>(result);
    }

    /**
     * 全角转半角、去控制字符、小写
     */
    public static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 12288) {
                sb.append(' ');
            } else if (c > 65280 && c < 65375) {
                sb.append((char) (c - 65248));
            } else if (c == '\u00a0') {
                sb.append(' ');
            } else if (c < 32 && c != '\n' && c != '\t') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase();
    }

    private static void flushCjk(StringBuilder buf, Set<String> out) {
        int len = buf.length();
        if (len == 0) {
            return;
        }
        if (len == 1) {
            addIfValid(buf.toString(), out);
        } else {
            for (int i = 0; i < len - 1; i++) {
                addIfValid(buf.substring(i, i + 2), out);
            }
        }
        buf.setLength(0);
    }

    private static void flushWord(StringBuilder buf, Set<String> out) {
        if (buf.length() == 0) {
            return;
        }
        String w = buf.toString();
        addIfValid(w, out);
        buf.setLength(0);
    }

    private static void addIfValid(String token, Set<String> out) {
        if (token.isEmpty() || STOP_WORDS.contains(token)) {
            return;
        }
        out.add(token);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    private static boolean isAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
    }
}
