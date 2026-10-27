package com.goocidata.agent.controller;

import com.goocidata.agent.common.BizException;
import com.goocidata.agent.common.R;
import com.goocidata.agent.config.AuthInterceptor;
import com.goocidata.agent.entity.SysUser;
import com.goocidata.agent.service.UsageStatService;
import com.goocidata.agent.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户管理接口（管理端）
 *
 * @author nzj
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UsageStatService usageStatService;

    @GetMapping
    public R<List<SysUser>> list(@RequestParam(required = false) String kw) {
        return R.ok(userService.list(kw));
    }

    @PostMapping("/save")
    public R<SysUser> save(@RequestBody SysUser input,
                           @RequestAttribute(AuthInterceptor.ATTR_USER) SysUser current) {
        if (input.getId() == null || input.getId().isEmpty()) {
            return R.ok(userService.create(input));
        }
        return R.ok(userService.update(input, current));
    }

    /**
     * 一键启用/停用（列表页开关）。停用会立刻踢下线，不用等令牌过期。
     */
    @PostMapping("/{id}/status")
    public R<SysUser> setStatus(@PathVariable String id,
                                @RequestBody StatusReq req,
                                @RequestAttribute(AuthInterceptor.ATTR_USER) SysUser current) {
        if (req == null || req.getStatus() == null) {
            throw new BizException(400, "缺少状态值");
        }
        return R.ok(userService.setStatus(id, req.getStatus(), current));
    }

    /**
     * 各用户的 token 消耗汇总（管理端列表页用）。
     * 这里是纯 token 数，金额由前端按统一单价换算，保证和仪表盘、聊天气泡口径一致。
     */
    @GetMapping("/usage")
    public R<Map<String, Object>> usage() {
        return R.ok(usageStatService.byUser());
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id,
                          @RequestAttribute(AuthInterceptor.ATTR_USER) SysUser current) {
        userService.delete(id, current);
        return R.ok();
    }

    @Data
    public static class StatusReq {
        /** 1 启用 0 停用 */
        private Integer status;
    }
}
