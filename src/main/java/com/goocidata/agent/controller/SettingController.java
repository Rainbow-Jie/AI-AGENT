package com.goocidata.agent.controller;

import com.goocidata.agent.common.R;
import com.goocidata.agent.mapper.SysSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统设置（管理端）
 *
 * @author nzj
 */
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class SettingController {

    private final SysSettingMapper settingMapper;

    @GetMapping
    public R<Map<String, String>> get() {
        Map<String, String> data = new LinkedHashMap<>();
        // 默认值，便于前端直接渲染
        data.put("siteName", "Agent Hub 智能体平台");
        data.put("siteDesc", "接入 DeepSeek，让知识库会说话");
        data.put("footerText", "");
        for (Map<String, Object> row : settingMapper.listAll()) {
            Object k = row.get("k");
            Object v = row.get("v");
            if (k != null) {
                data.put(String.valueOf(k), v == null ? "" : String.valueOf(v));
            }
        }
        return R.ok(data);
    }

    @PostMapping
    public R<Void> save(@RequestBody Map<String, String> input) {
        input.forEach((k, v) -> {
            if (k != null && !k.trim().isEmpty()) {
                settingMapper.upsert(k, v == null ? "" : v);
            }
        });
        return R.ok();
    }
}
