package com.goocidata.agent.controller;

import com.goocidata.agent.common.R;
import com.goocidata.agent.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型单价
 * <p>
 * 读接口放在 /api/ 下而不是 /api/admin/：聊天气泡要按实时单价算「本轮花费」，
 * 普通用户也得能拿到这张表。价格本身不是敏感信息（官网公开）。
 *
 * @author nzj
 */
@RestController
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;

    /** 当前生效的单价（含默认模型对应的 peak/offPeak 两组数） */
    @GetMapping("/api/pricing")
    public R<Map<String, Object>> current() {
        return R.ok(pricingService.current());
    }

    /** 管理端「立即刷新」 */
    @PostMapping("/api/admin/pricing/refresh")
    public R<Map<String, Object>> refresh() {
        Map<String, Object> snap = pricingService.refresh();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", snap != null);
        data.put("result", pricingService.current());
        return R.ok(data);
    }
}
