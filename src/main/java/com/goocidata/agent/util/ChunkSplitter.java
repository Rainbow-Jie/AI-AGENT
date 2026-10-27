package com.goocidata.agent.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化文本切片器。
 * <p>
 * 与朴素「按字数硬切」的区别：
 * <ol>
 *     <li>以 Markdown 标题为自然边界切分，章节不会被拦腰截断；</li>
 *     <li>每个切片头部注入「文档 › 章节」上下文，即使正文全是表格数据，
 *         也能被「饮品」「气泡水」这类主题词召回；</li>
 *     <li>表格整块保留，超长时按行切片并重复表头，避免出现只有 {@code |---|} 却没有表头的碎片；</li>
 *     <li>不以固定长度打断句子，块内语义完整。</li>
 * </ol>
 *
 * @author nzj
 */
public final class ChunkSplitter {

    /** 每块目标字符数 */
    public static final int CHUNK_SIZE = 600;
    /** 单块硬上限，超过则按行再切 */
    private static final int HARD_LIMIT = 900;
    /** 单个表格超过这么多行就拆片（拆片时重复表头） */
    private static final int TABLE_ROWS_PER_CHUNK = 16;
    /** 小于该长度的块尝试并入上一块 */
    private static final int TINY = 50;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    /** 标题层级上限 */
    private static final int MAX_LEVEL = 6;

    private ChunkSplitter() {
    }

    public static List<String> split(String text) {
        return split(text, null);
    }

    /**
     * @param text     原文
     * @param docTitle 文档标题（一般取文件名去扩展名），为空时用文中一级标题
     */
    public static List<String> split(String text, String docTitle) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<Block> blocks = parse(normalized, docTitle);
        return pack(blocks);
    }

    // ------------------------------------------------------------------ 解析

    private static List<Block> parse(String text, String docTitle) {
        List<Block> blocks = new ArrayList<>();
        String[] headings = new String[MAX_LEVEL + 1];
        String docName = docTitle == null ? "" : docTitle.trim();

        StringBuilder para = new StringBuilder();
        List<String> tableRows = new ArrayList<>();

        String[] lines = text.split("\n", -1);
        for (String raw : lines) {
            String line = raw.trim();

            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                flushPara(blocks, para, contextPath(headings, docName));
                flushTable(blocks, tableRows, contextPath(headings, docName));
                tableRows = new ArrayList<>();

                int level = Math.min(m.group(1).length(), MAX_LEVEL);
                String title = m.group(2).trim();
                if (level == 1 && docName.isEmpty()) {
                    docName = title;
                }
                headings[level] = title;
                for (int i = level + 1; i <= MAX_LEVEL; i++) {
                    headings[i] = null;
                }
                continue;
            }

            if (line.isEmpty()) {
                flushPara(blocks, para, contextPath(headings, docName));
                // 空行不结束表格：Markdown 表格中间极少出现空行，但有的编辑器会插
                continue;
            }

            if (isTableRow(line)) {
                flushPara(blocks, para, contextPath(headings, docName));
                tableRows.add(line);
            } else {
                flushTable(blocks, tableRows, contextPath(headings, docName));
                tableRows = new ArrayList<>();
                if (para.length() > 0) {
                    para.append('\n');
                }
                para.append(line);
            }
        }
        flushPara(blocks, para, contextPath(headings, docName));
        flushTable(blocks, tableRows, contextPath(headings, docName));
        return blocks;
    }

    /**
     * 组装「文档 › 一级 › 二级」形式的上下文，相邻重复项去重
     */
    private static String contextPath(String[] headings, String docName) {
        Set<String> parts = new LinkedHashSet<>();
        if (docName != null && !docName.isEmpty()) {
            parts.add(docName);
        }
        for (int i = 1; i <= MAX_LEVEL; i++) {
            if (headings[i] != null && !headings[i].isEmpty()) {
                parts.add(headings[i]);
            }
        }
        String joined = String.join(" › ", parts);
        return joined;
    }

    private static void flushPara(List<Block> blocks, StringBuilder buf, String ctx) {
        String s = buf.toString().trim();
        buf.setLength(0);
        if (!s.isEmpty()) {
            blocks.add(new Block(ctx, s));
        }
    }

    private static void flushTable(List<Block> blocks, List<String> rows, String ctx) {
        if (rows.isEmpty()) {
            return;
        }
        int headerLen = 1;
        if (rows.size() >= 2 && isSeparatorRow(rows.get(1))) {
            headerLen = 2;
        }
        List<String> header = new ArrayList<>(rows.subList(0, Math.min(headerLen, rows.size())));

        if (rows.size() <= TABLE_ROWS_PER_CHUNK) {
            blocks.add(new Block(ctx, String.join("\n", rows)));
            return;
        }
        int per = Math.max(4, TABLE_ROWS_PER_CHUNK - header.size());
        for (int i = header.size(); i < rows.size(); i += per) {
            List<String> piece = new ArrayList<>(header);
            piece.addAll(rows.subList(i, Math.min(i + per, rows.size())));
            blocks.add(new Block(ctx, String.join("\n", piece)));
        }
    }

    // ------------------------------------------------------------------ 打包

    private static List<String> pack(List<Block> blocks) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String bufCtx = null;

        for (Block b : blocks) {
            String ctx = b.context == null ? "" : b.context.trim();
            String head = ctx.isEmpty() ? "" : "【" + ctx + "】\n";
            String piece = head + b.text;

            if (piece.length() > HARD_LIMIT) {
                out.addAll(flushAndReset(buf));
                bufCtx = null;
                out.addAll(hardSplit(head, b.text));
                continue;
            }
            boolean ctxChanged = buf.length() > 0 && !ctx.equals(bufCtx);
            boolean tooLong = buf.length() > 0 && buf.length() + piece.length() + 1 > CHUNK_SIZE;
            if (ctxChanged || tooLong) {
                out.addAll(flushAndReset(buf));
                bufCtx = null;
            }
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(piece);
            bufCtx = ctx;
        }
        out.addAll(flushAndReset(buf));
        return mergeTiny(out);
    }

    private static List<String> flushAndReset(StringBuilder buf) {
        List<String> one = new ArrayList<>(1);
        String s = buf.toString().trim();
        buf.setLength(0);
        if (!s.isEmpty()) {
            one.add(s);
        }
        return one;
    }

    private static List<String> hardSplit(String head, String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (buf.length() > 0 && buf.length() + line.length() + 1 > CHUNK_SIZE) {
                out.add(head + buf.toString());
                buf.setLength(0);
            }
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(line);
        }
        if (buf.length() > 0) {
            out.add(head + buf.toString());
        }
        return out;
    }

    /**
     * 过短的碎块并回上一块（同一上下文才合并，避免串章节）
     */
    private static List<String> mergeTiny(List<String> chunks) {
        List<String> merged = new ArrayList<>();
        for (String c : chunks) {
            if (!merged.isEmpty() && c.length() < TINY) {
                int last = merged.size() - 1;
                String prev = merged.get(last);
                if (sameContext(prev, c) && prev.length() + c.length() <= HARD_LIMIT) {
                    merged.set(last, prev + "\n" + c);
                    continue;
                }
            }
            merged.add(c);
        }
        return merged;
    }

    private static boolean sameContext(String a, String b) {
        return headerOf(a).equals(headerOf(b));
    }

    private static String headerOf(String chunk) {
        int idx = chunk.indexOf('\n');
        String first = idx < 0 ? chunk : chunk.substring(0, idx);
        return first.startsWith("【") && first.endsWith("】") ? first : "";
    }

    // ------------------------------------------------------------------ 判定

    private static boolean isTableRow(String line) {
        return line.startsWith("|") || (line.contains("|") && line.endsWith("|"));
    }

    private static boolean isSeparatorRow(String line) {
        if (!line.startsWith("|")) {
            return false;
        }
        boolean hasDash = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '-') {
                hasDash = true;
            } else if (c != '|' && c != ':' && c != ' ') {
                return false;
            }
        }
        return hasDash;
    }

    /** 解析中间产物：上下文 + 内容 */
    private static final class Block {
        private final String context;
        private final String text;

        private Block(String context, String text) {
            this.context = context;
            this.text = text;
        }
    }
}
