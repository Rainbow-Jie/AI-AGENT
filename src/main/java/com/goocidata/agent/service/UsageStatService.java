package com.goocidata.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goocidata.agent.dto.UsageRow;
import com.goocidata.agent.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按用户汇总 token 消耗（管理端）
 * <p>
 * 数据源只有一个：chat_message.usage。每条回答落库时把本轮所有模型调用的
 * usage 累加后写成 JSON，所以「轮次」是问答回合数，不是 HTTP 调用次数。
 * <p>
 * 这里只做「算 token」，不换算钱：单价写在前端 api.js 的 TOKEN_PRICE 里
 * （峰谷分时，DeepSeek 计价），聊天气泡和仪表盘都用那一套。金额交给前端算，
 * 三个页面才会是同一个数，不会出现两处对不上的情况。
 *
 * @author nzj
 */
@Service
@RequiredArgsConstructor
public class UsageStatService {

    private final ChatMessageMapper messageMapper;

    /**
     * 返回 {@code users}（userId → 统计）和 {@code total}（全员合计）。
     * 列表里没有的账号表示从没对话过，前端按 0 处理即可。
     */
    public Map<String, Object> byUser() {
        Map<String, Accumulator> accs = new LinkedHashMap<>();
        Accumulator total = new Accumulator();

        for (UsageRow row : messageMapper.listUsagesByUser()) {
            if (row.getUserId() == null) {
                continue;
            }
            accs.computeIfAbsent(row.getUserId(), k -> new Accumulator()).add(row.getUsage());
        }
        for (Accumulator acc : accs.values()) {
            // 合计必须在解析完之后再加，避免重复解析同一份 JSON
            total.merge(acc);
        }

        Map<String, Object> users = new LinkedHashMap<>();
        accs.forEach((userId, acc) -> users.put(userId, acc.toMap()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", users);
        result.put("total", total.toMap());
        return result;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class Accumulator {
        long messages;
        long promptTokens;
        long completionTokens;
        long totalTokens;
        long cacheHitTokens;
        long cacheMissTokens;
        /** 带缓存字段的轮次；为 0 表示服务端没返回缓存信息，命中率不可信 */
        long cacheAwareMessages;

        void add(String json) {
            JsonNode n;
            try {
                n = MAPPER.readTree(json);
            } catch (Exception e) {
                // 单条脏数据不该让整页统计失败
                return;
            }
            if (n == null || !n.isObject()) {
                return;
            }
            long prompt = n.path("promptTokens").asLong(0);
            long hit = n.path("cacheHitTokens").asLong(-1);
            long miss = n.path("cacheMissTokens").asLong(-1);

            messages++;
            promptTokens += prompt;
            completionTokens += n.path("completionTokens").asLong(0);
            totalTokens += n.path("totalTokens").asLong(0);

            if (hit >= 0 || miss >= 0) {
                cacheAwareMessages++;
                long hitEff = Math.max(0, hit);
                // 只给了命中数时用「输入总数 - 命中」倒推未命中
                long missEff = miss >= 0 ? miss : Math.max(0, prompt - hitEff);
                cacheHitTokens += hitEff;
                cacheMissTokens += missEff;
            }
        }

        void merge(Accumulator o) {
            messages += o.messages;
            promptTokens += o.promptTokens;
            completionTokens += o.completionTokens;
            totalTokens += o.totalTokens;
            cacheHitTokens += o.cacheHitTokens;
            cacheMissTokens += o.cacheMissTokens;
            cacheAwareMessages += o.cacheAwareMessages;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("messages", messages);
            m.put("promptTokens", promptTokens);
            m.put("completionTokens", completionTokens);
            m.put("totalTokens", totalTokens);
            m.put("cacheHitTokens", cacheHitTokens);
            m.put("cacheMissTokens", cacheMissTokens);
            long cacheSum = cacheHitTokens + cacheMissTokens;
            // 没有缓存信息时不给 cacheHitRate，前端 estimateCost 会按「全部未命中」算，
            // 这比默认 0% 命中（少算钱）更保守，不会让人以为很便宜
            if (cacheAwareMessages > 0 && cacheSum > 0) {
                m.put("cacheHitRate", Math.round(cacheHitTokens * 1000D / cacheSum) / 10D);
            }
            return m;
        }
    }
}
