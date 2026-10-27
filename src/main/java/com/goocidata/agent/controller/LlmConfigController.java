package com.goocidata.agent.controller;

import com.goocidata.agent.common.R;
import com.goocidata.agent.entity.LlmConfig;
import com.goocidata.agent.service.LlmConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置接口（管理端）
 *
 * @author nzj
 */
@RestController
@RequestMapping("/api/admin/models")
@RequiredArgsConstructor
public class LlmConfigController {

    private final LlmConfigService configService;

    @GetMapping
    public R<List<LlmConfig>> list() {
        return R.ok(configService.list());
    }

    @PostMapping("/save")
    public R<LlmConfig> save(@RequestBody LlmConfig input) {
        LlmConfig saved = configService.save(input);
        saved.setApiKeyMask(null);
        return R.ok(saved);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        configService.delete(id);
        return R.ok();
    }

    /**
     * 测试连接：传 id 测已存配置，或直接传表单内容测未保存的
     */
    @PostMapping("/test")
    public R<Map<String, Object>> test(@RequestBody(required = false) LlmConfig input,
                                       @RequestParam(required = false) String id) {
        String reply;
        // 表单里有 BaseURL 或模型名 → 按表单内容测（支持未保存的配置）
        boolean fromForm = input != null && (input.getBaseUrl() != null || input.getModel() != null);
        if (fromForm) {
            reply = configService.testInline(input);
        } else {
            // 只传 id → 测已保存的配置（id 可能来自 body，也可能来自 query）
            String targetId = (input != null && input.getId() != null) ? input.getId() : id;
            reply = configService.test(targetId);
        }
        Map<String, Object> data = new HashMap<>(2);
        data.put("reply", reply);
        data.put("ok", true);
        return R.ok(data);
    }
}
