package com.goocidata.agent.service;

import com.goocidata.agent.common.BizException;
import com.goocidata.agent.common.IdGen;
import com.goocidata.agent.entity.LlmConfig;
import com.goocidata.agent.mapper.AgentInfoMapper;
import com.goocidata.agent.mapper.LlmConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 模型配置服务
 *
 * @author nzj
 */
@Service
@RequiredArgsConstructor
public class LlmConfigService {

    private final LlmConfigMapper configMapper;
    private final AgentInfoMapper agentMapper;
    private final LlmClient llmClient;

    public List<LlmConfig> list() {
        List<LlmConfig> list = configMapper.listAll();
        list.forEach(c -> c.setApiKeyMask(mask(c.getApiKey())));
        return list;
    }

    public LlmConfig getWithKey(String id) {
        LlmConfig cfg = configMapper.findById(id);
        if (cfg == null) {
            throw new BizException("模型配置不存在");
        }
        return cfg;
    }

    @Transactional
    public LlmConfig save(LlmConfig input) {
        if (input.getName() == null || input.getName().trim().isEmpty()) {
            throw new BizException(400, "请填写配置名称");
        }
        if (input.getBaseUrl() == null || input.getBaseUrl().trim().isEmpty()) {
            throw new BizException(400, "请填写 Base URL");
        }
        if (input.getModel() == null || input.getModel().trim().isEmpty()) {
            throw new BizException(400, "请填写模型名称");
        }
        input.setName(input.getName().trim());
        input.setBaseUrl(normalizeBaseUrl(input.getBaseUrl()));
        input.setModel(input.getModel().trim());
        if (input.getTemperature() == null) {
            input.setTemperature(0.7D);
        }
        if (input.getMaxTokens() == null || input.getMaxTokens() <= 0) {
            input.setMaxTokens(2048);
        }
        if (input.getStatus() == null) {
            input.setStatus(1);
        }

        if (input.getId() == null || input.getId().isEmpty()) {
            input.setId(IdGen.next());
            input.setApiKey(input.getApiKey() == null ? "" : input.getApiKey().trim());
            if (input.getIsDefault() == null) {
                input.setIsDefault(configMapper.count() == 0 ? 1 : 0);
            }
            input.setCreatedAt(IdGen.now());
            input.setUpdatedAt(input.getCreatedAt());
            if (input.getIsDefault() == 1) {
                configMapper.clearDefault();
            }
            configMapper.insert(input);
        } else {
            LlmConfig db = getWithKey(input.getId());
            String incoming = input.getApiKey();
            // 前端未改动密钥时不覆盖
            if (incoming == null || incoming.trim().isEmpty() || incoming.contains("****")) {
                input.setApiKey(db.getApiKey());
            } else {
                input.setApiKey(incoming.trim());
            }
            input.setUpdatedAt(IdGen.now());
            if (input.getIsDefault() != null && input.getIsDefault() == 1) {
                configMapper.clearDefault();
            }
            configMapper.update(input);
        }
        return input;
    }

    public void delete(String id) {
        LlmConfig db = configMapper.findById(id);
        if (db == null) {
            return;
        }
        long used = agentMapper.list(null, false).stream()
                .filter(a -> id.equals(a.getLlmConfigId()))
                .count();
        if (used > 0) {
            throw new BizException("该配置已被 " + used + " 个智能体使用，请先解绑");
        }
        configMapper.delete(id);
    }

    public String test(String id) {
        return llmClient.test(getWithKey(id));
    }

    public String testInline(LlmConfig input) {
        if (input.getId() != null && !input.getId().isEmpty()) {
            LlmConfig db = configMapper.findById(input.getId());
            if (db != null && (input.getApiKey() == null || input.getApiKey().isEmpty() || input.getApiKey().contains("****"))) {
                input.setApiKey(db.getApiKey());
            }
        }
        input.setBaseUrl(normalizeBaseUrl(input.getBaseUrl()));
        return llmClient.test(input);
    }

    private String normalizeBaseUrl(String url) {
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
