package com.goocidata.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goocidata.agent.entity.LlmConfig;
import com.goocidata.agent.mapper.LlmConfigMapper;
import com.goocidata.agent.mapper.SysSettingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型单价：每天凌晨从 DeepSeek 官方定价页抓取，用于费用估算。
 * <p>
 * 为什么不用接口：DeepSeek 没有开放"查价格"的 API，官方口径只在这张文档页上。
 * 页面是 Docusaurus 静态渲染的，价格直接写在 HTML 表格里（不是 JS 后填），
 * 所以同步解析即可，不需要跑浏览器。
 * <p>
 * 抓取失败的策略是「宁可旧、不可错」：失败只记录错误，继续用上一次成功的价格，
 * 绝不把价格写成 0——否则页面会显示成「免费」，比数字过时严重得多。
 *
 * @author nzj
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingService {

    /** sys_setting 里的键 */
    private static final String KEY = "pricing";

    /**
     * 官方中文定价页。用中文页而不是英文页：中文页直接是人民币，
     * 英文页是美元，换算汇率又是另一个会变的数，不如直接用官方的人民币口径。
     */
    @Value("${agent-hub.pricing.source:https://api-docs.deepseek.com/zh-cn/quick_start/pricing}")
    private String sourceUrl;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 超过这个时长没更新成功就标记为「数据偏旧」 */
    private static final long STALE_HOURS = 48;

    private final SysSettingMapper settingMapper;
    private final LlmConfigMapper configMapper;
    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 内存快照，避免每次请求都查库 */
    private volatile Map<String, Object> snapshot;

    /* ================================================================
       对外接口
       ================================================================ */

    /**
     * 启动后补一次：只在「从来没有价格」或「上次成功已经超过一天」时才抓，
     * 避免频繁重启把官方页面刷成攻击。放到异步线程，不拖慢启动。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Map<String, Object> cur = current();
        if (Boolean.TRUE.equals(cur.get("ok")) && !Boolean.TRUE.equals(cur.get("stale"))) {
            return;
        }
        new Thread(() -> refresh(), "pricing-warmup").start();
    }

    /** 每天凌晨 3:10（北京时间）更新一次；显式指定时区，避免服务器是 UTC 时半夜跑偏 */
    @Scheduled(cron = "0 10 3 * * ?", zone = "Asia/Shanghai")
    public void scheduledRefresh() {
        log.info("定时任务：开始抓取 DeepSeek 官方定价");
        refresh();
    }

    /**
     * 抓一次并落库。返回 null 表示失败（失败时旧价格保持不变）。
     */
    public synchronized Map<String, Object> refresh() {
        try {
            String html = download();
            Map<String, Map<String, Map<String, Double>>> models = parse(html);
            if (models.isEmpty()) {
                throw new IllegalStateException("页面结构可能变了，没解析出任何价格");
            }
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("ok", true);
            snap.put("currency", "CNY");
            snap.put("unit", "元/百万 tokens");
            snap.put("source", sourceUrl);
            snap.put("fetchedAt", LocalDateTime.now().format(TS));
            snap.put("models", models);
            snap.put("error", null);
            snap.put("errorAt", null);

            settingMapper.upsert(KEY, json.writeValueAsString(snap));
            snapshot = snap;
            log.info("定价已更新：{} 个模型（{}）", models.size(), String.join("、", models.keySet()));
            return snap;
        } catch (Exception e) {
            log.warn("抓取 DeepSeek 定价失败，继续沿用上一次的价格：{}", e.toString());
            markError(e);
            return null;
        }
    }

    /**
     * 给前端用的视图：把「当前默认模型」对应的价格单独拎出来，
     * 前端 estimateCost 直接取 peak/offPeak 两组数即可。
     */
    public Map<String, Object> current() {
        Map<String, Object> snap = load();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean ok = Boolean.TRUE.equals(snap.get("ok"));

        out.put("ok", ok);
        out.put("currency", snap.getOrDefault("currency", "CNY"));
        out.put("unit", snap.getOrDefault("unit", "元/百万 tokens"));
        out.put("source", sourceUrl);
        out.put("fetchedAt", snap.get("fetchedAt"));
        out.put("error", snap.get("error"));

        String fetchedAt = str(snap.get("fetchedAt"));
        out.put("stale", fetchedAt.isEmpty() || isStale(fetchedAt));

        Map<String, Map<String, Map<String, Double>>> models = castModels(snap.get("models"));
        out.put("models", models);

        String model = currentModel();
        Map<String, Map<String, Double>> picked = models.get(model);
        if (picked == null && !models.isEmpty()) {
            // 配置里写的模型名和官方表里的名字对不上时，退到 flash，再退到第一个
            picked = models.get("deepseek-flash");
            if (picked == null) {
                picked = models.values().iterator().next();
            }
        }
        out.put("model", model);
        out.put("price", picked);
        return out;
    }

    /* ================================================================
       抓取
       ================================================================ */

    private String download() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(sourceUrl))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (compatible; AgentHub-PricingBot/1.0)")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /* ================================================================
       解析：把 HTML 表格还原成二维网格，再按语义找价格
       ================================================================ */

    private static final Pattern TABLE = Pattern.compile("<table[^>]*>(.*?)</table>", Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[dh]([^>]*)>(.*?)</t[dh]>", Pattern.DOTALL);
    private static final Pattern SPAN = Pattern.compile("(rowspan|colspan)\\s*=\\s*\"(\\d+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern PRICE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*元");

    /**
     * @return 模型名 → {"peak": {...}, "offPeak": {...}}
     */
    Map<String, Map<String, Map<String, Double>>> parse(String html) {
        List<List<String>> grid = firstTableGrid(html);
        if (grid.isEmpty()) {
            return new LinkedHashMap<>();
        }

        // 1. 表头行里挑出模型名（就是含 deepseek- 的那几个单元格）
        List<String> models = new ArrayList<>();
        int modelRowIdx = -1;
        for (int r = 0; r < grid.size() && modelRowIdx < 0; r++) {
            List<String> row = grid.get(r);
            List<String> found = new ArrayList<>();
            for (String cell : row) {
                if (cell.startsWith("deepseek-")) {
                    found.add(modelName(cell));
                }
            }
            if (!found.isEmpty()) {
                models.addAll(found);
                modelRowIdx = r;
            }
        }
        if (models.isEmpty()) {
            return new LinkedHashMap<>();
        }

        // 2. 表头里模型名之前的列数（「模型」那一列），用来对齐价格列
        int priceColStart = -1;
        List<String> headRow = grid.get(modelRowIdx);
        for (int c = 0; c < headRow.size(); c++) {
            if (headRow.get(c).startsWith("deepseek-")) {
                priceColStart = c;
                break;
            }
        }

        // 3. 逐行找价格：类别看「缓存命中 / 未命中 / 输出」，时段看「空闲 / 高峰」
        Map<String, Map<String, Map<String, Double>>> result = new LinkedHashMap<>();
        for (String m : models) {
            result.put(m, new LinkedHashMap<>());
        }
        String category = null;
        for (List<String> row : grid) {
            String rowText = String.join(" ", row);
            String cat = detectCategory(rowText);
            if (cat != null) {
                category = cat;
            }
            if (category == null) {
                continue;
            }
            String period = rowText.contains("高峰") ? "peak"
                    : rowText.contains("空闲") ? "offPeak" : null;
            if (period == null) {
                continue;
            }
            List<Double> values = new ArrayList<>();
            for (int c = priceColStart; c < row.size(); c++) {
                Matcher m = PRICE.matcher(row.get(c));
                if (m.find()) {
                    values.add(Double.parseDouble(m.group(1)));
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            for (int i = 0; i < models.size(); i++) {
                // 行内价格个数少于模型数时，用最后一个兜底，避免数组越界丢数据
                Double v = values.get(Math.min(i, values.size() - 1));
                result.get(models.get(i))
                        .computeIfAbsent(period, k -> new LinkedHashMap<>())
                        .put(category, v);
            }
        }

        // 4. 三个档位不全的模型直接丢掉，半套价格算出来的费用是错的
        Map<String, Map<String, Map<String, Double>>> usable = new LinkedHashMap<>();
        result.forEach((m, byPeriod) -> {
            Map<String, Double> peak = byPeriod.get("peak");
            Map<String, Double> off = byPeriod.get("offPeak");
            if (complete(peak) && complete(off)) {
                usable.put(m, byPeriod);
            } else {
                log.warn("定价解析结果不完整，已跳过模型 {}：{}", m, byPeriod);
            }
        });
        return usable;
    }

    private static boolean complete(Map<String, Double> p) {
        return p != null
                && p.getOrDefault("cacheHit", -1D) >= 0
                && p.getOrDefault("cacheMiss", -1D) > 0
                && p.getOrDefault("output", -1D) > 0;
    }

    private static String detectCategory(String text) {
        if (text.contains("未命中")) {
            return "cacheMiss";
        }
        if (text.contains("缓存命中")) {
            return "cacheHit";
        }
        if (text.contains("输出") && text.contains("tokens")) {
            return "output";
        }
        return null;
    }

    /**
     * 只取第一个表格，并把 rowspan/colspan 展开成规整网格。
     * <p>
     * 不展开的话，「价格」那格跨了 6 行，从第 2 行起列就会整体左移，
     * 后面按列取价格会全部错位。
     */
    private List<List<String>> firstTableGrid(String html) {
        List<List<String>> grid = new ArrayList<>();
        Matcher tm = TABLE.matcher(html);
        if (!tm.find()) {
            return grid;
        }
        List<String> rows = new ArrayList<>();
        Matcher rm = ROW.matcher(tm.group(1));
        while (rm.find()) {
            rows.add(rm.group(1));
        }

        // (行,列) → 被上方 rowspan 占住的值
        Map<Long, String> carried = new HashMap<>();
        int width = 0;
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = new ArrayList<>();
            Matcher cm = CELL.matcher(rows.get(r));
            int col = 0;
            while (cm.find()) {
                while (carried.containsKey(key(r, col))) {
                    row.add(carried.get(key(r, col)));
                    col++;
                }
                String text = clean(cm.group(2));
                int rowspan = 1;
                int colspan = 1;
                Matcher sm = SPAN.matcher(cm.group(1) == null ? "" : cm.group(1));
                while (sm.find()) {
                    if ("rowspan".equalsIgnoreCase(sm.group(1))) {
                        rowspan = Math.max(1, Integer.parseInt(sm.group(2)));
                    } else {
                        colspan = Math.max(1, Integer.parseInt(sm.group(2)));
                    }
                }
                for (int i = 0; i < colspan; i++) {
                    row.add(text);
                    for (int k = 1; k < rowspan; k++) {
                        carried.put(key(r + k, col), text);
                    }
                    col++;
                }
            }
            while (carried.containsKey(key(r, col))) {
                row.add(carried.get(key(r, col)));
                col++;
            }
            width = Math.max(width, row.size());
            grid.add(row);
        }
        for (List<String> row : grid) {
            while (row.size() < width) {
                row.add("");
            }
        }
        return grid;
    }

    private static long key(int r, int c) {
        return (long) r * 1000L + c;
    }

    /**
     * 表头里的模型名带脚注，解析出来是「deepseek-flash (1)」。
     * 不去掉就跟 llm_config 里填的模型名对不上，会退到错误的价格。
     */
    private static String modelName(String cell) {
        return cell.replaceAll("[\\s\\u00a0]*\\(\\d+\\)\\s*$", "").trim();
    }

    private static String clean(String raw) {
        String t = TAG.matcher(raw).replaceAll(" ");
        t = t.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return t.replaceAll("\\s+", " ").trim();
    }

    /* ================================================================
       存储
       ================================================================ */

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        Map<String, Object> cached = snapshot;
        if (cached != null) {
            return cached;
        }
        try {
            String raw = settingMapper.get(KEY);
            if (raw != null && !raw.isEmpty()) {
                snapshot = json.readValue(raw, Map.class);
                return snapshot;
            }
        } catch (Exception e) {
            log.warn("读取已保存的定价失败：{}", e.getMessage());
        }
        snapshot = new LinkedHashMap<>();
        return snapshot;
    }

    private void markError(Exception e) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>(load());
            snap.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            snap.put("errorAt", LocalDateTime.now().format(TS));
            settingMapper.upsert(KEY, json.writeValueAsString(snap));
            snapshot = snap;
        } catch (Exception ignore) {
            // 记录失败本身不该再抛异常
        }
    }

    private boolean isStale(String fetchedAt) {
        try {
            return LocalDateTime.parse(fetchedAt, TS).plusHours(STALE_HOURS).isBefore(LocalDateTime.now());
        } catch (Exception e) {
            return true;
        }
    }

    /** 当前智能体实际在用的模型名，用来选对应的价格 */
    private String currentModel() {
        try {
            LlmConfig def = configMapper.findDefault();
            if (def != null && def.getModel() != null && !def.getModel().trim().isEmpty()) {
                return def.getModel().trim();
            }
        } catch (Exception e) {
            log.debug("读取默认模型失败：{}", e.getMessage());
        }
        return "deepseek-flash";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Map<String, Double>>> castModels(Object raw) {
        if (raw instanceof Map) {
            return (Map<String, Map<String, Map<String, Double>>>) raw;
        }
        return new LinkedHashMap<>();
    }
}
