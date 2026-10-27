package com.goocidata.agent.controller;

import com.goocidata.agent.common.BizException;
import com.goocidata.agent.common.R;
import com.goocidata.agent.config.AuthInterceptor;
import com.goocidata.agent.entity.SysUser;
import com.goocidata.agent.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口
 *
 * @author nzj
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginReq req) {
        return R.ok(authService.login(req.getUsername(), req.getPassword()));
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            authService.logout(auth.substring(7));
        }
        String token = request.getHeader("X-Token");
        if (token != null) {
            authService.logout(token);
        }
        return R.ok();
    }

    @GetMapping("/me")
    public R<SysUser> me(@RequestAttribute(AuthInterceptor.ATTR_USER) SysUser user) {
        return R.ok(user);
    }

    @PostMapping("/password")
    public R<Void> changePassword(@RequestAttribute(AuthInterceptor.ATTR_USER) SysUser user,
                                  @RequestBody PasswordReq req) {
        if (req.getOldPassword() == null || req.getNewPassword() == null) {
            throw new BizException(400, "请填写原密码和新密码");
        }
        authService.changePassword(user, req.getOldPassword(), req.getNewPassword());
        return R.ok();
    }

    /**
     * 修改自己的资料（昵称 / 头像），任何登录用户都可调用
     */
    @PostMapping("/profile")
    public R<SysUser> updateProfile(@RequestAttribute(AuthInterceptor.ATTR_USER) SysUser user,
                                    @RequestBody ProfileReq req) {
        return R.ok(authService.updateProfile(user, req.getNickname(), req.getAvatar()));
    }

    /**
     * 前端用于判断系统是否已初始化（当前恒为已初始化）
     */
    @GetMapping("/init-status")
    public R<Map<String, Object>> initStatus() {
        Map<String, Object> data = new HashMap<>(2);
        data.put("initialized", true);
        return R.ok(data);
    }

    @Data
    public static class LoginReq {
        private String username;
        private String password;
    }

    @Data
    public static class PasswordReq {
        private String oldPassword;
        private String newPassword;
    }

    @Data
    public static class ProfileReq {
        private String nickname;
        /** data URL；传空串表示恢复默认（昵称首字）头像 */
        private String avatar;
    }
}
