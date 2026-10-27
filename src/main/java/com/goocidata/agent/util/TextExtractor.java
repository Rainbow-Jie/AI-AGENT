package com.goocidata.agent.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 文档纯文本抽取
 *
 * @author nzj
 */
@Slf4j
public final class TextExtractor {

    private TextExtractor() {
    }

    public static String extract(MultipartFile file, String ext) throws Exception {
        byte[] bytes = file.getBytes();
        switch (ext) {
            case "pdf":
                return fromPdf(bytes);
            case "docx":
                return fromDocx(bytes);
            case "doc":
                throw new IllegalArgumentException("暂不支持 .doc 旧格式，请另存为 .docx 后上传");
            case "txt":
            case "md":
            case "markdown":
            case "csv":
            case "log":
            case "json":
            case "sql":
            case "java":
                return fromPlain(bytes);
            default:
                // 未知后缀按纯文本兜底尝试
                return fromPlain(bytes);
        }
    }

    private static String fromPdf(byte[] bytes) throws Exception {
        try (PDDocument doc = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    private static String fromDocx(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    /**
     * 纯文本读取，UTF-8 优先，失败回落 GBK
     */
    private static String fromPlain(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception e) {
                log.warn("GBK 解码失败，回落 UTF-8");
            }
        }
        return utf8;
    }

    /**
     * 清洗：统一换行、压缩多余空行与空格
     */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
        s = s.replaceAll("[ \\t\\x0B\\f]+", " ");
        s = s.replaceAll("\\n{3,}", "\n\n");
        // 去掉 PDF 常见的连字符断行
        s = s.replaceAll("(\\p{IsHan})\\n(\\p{IsHan})", "$1$2");
        return s.trim();
    }
}
