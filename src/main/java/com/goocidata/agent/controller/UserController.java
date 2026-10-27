package com.goocidata.agent.controller;

import com.goocidata.agent.common.R;
import com.goocidata.agent.config.AuthInterceptor;
import com.goocidata.agent.entity.SysUser;
import com.goocidata.agent.service.UserService;
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

    @GetMapping
    public R<List<SysUser>> list(@RequestParam(required = false) String kw) {
        return R.ok(userService.list(kw));
    }

    @PostMapping("/save")
    public R<SysUser> save(@RequestBody SysUser input) {
        if (input.getId() == null || input.getId().isEmpty()) {
            return R.ok(userService.create(input));
        }
        return R.ok(userService.update(input));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id,
                          @RequestAttribute(AuthInterceptor.ATTR_USER) SysUser current) {
        userService.delete(id, current);
        return R.ok();
    }
}
