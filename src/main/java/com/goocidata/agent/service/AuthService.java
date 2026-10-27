package com.goocidata.agent.service;

import com.goocidata.agent.common.BizException;
import com.goocidata.agent.common.IdGen;
import com.goocidata.agent.config.TokenStore;
import com.goocidata.agent.entity.SysUser;
import com.goocidata.agent.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证服务
 *
 * @author nzj
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final TokenStore tokenStore;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public Map<String, Object> login(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            throw new BizException(400, "请输入用户名和密码");
        }
        SysUser user = userMapper.findByUsername(username.trim());
        if (user == null || !encoder.matches(password, user.getPassword())) {
            throw new BizException(400, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(403, "账号已被停用，请联系管理员");
        }
        userMapper.touchLogin(user.getId(), IdGen.now());
        user.setLastLoginAt(IdGen.now());

        String token = tokenStore.issue(user);
        Map<String, Object> result = new HashMap<>(4);
        result.put("token", token);
        result.put("user", user);
        return result;
    }

    public void logout(String token) {
        tokenStore.revoke(token);
    }

    /** 头像上限：前端会先压到 200×200 的 JPEG（约 15KB），这里留足余量防绕过 */
    private static final int MAX_AVATAR_LENGTH = 400_000;

    /**
     * 修改自己的资料（昵称 + 头像）。
     * 用户名不允许改——它是登录凭据，且被会话、审计等按 username 关联。
     */
    public SysUser updateProfile(SysUser current, String nickname, String avatar) {
        SysUser db = userMapper.findById(current.getId());
        if (db == null) {
            throw new BizException(401, "用户不存在");
        }
        String nick = nickname == null ? "" : nickname.trim();
        if (nick.isEmpty()) {
            throw new BizException(400, "昵称不能为空");
        }
        if (nick.length() > 24) {
            throw new BizException(400, "昵称最多 24 个字符");
        }
        String av = avatar == null ? "" : avatar.trim();
        if (!av.isEmpty()) {
            if (!av.startsWith("data:image/")) {
                throw new BizException(400, "头像格式不正确，请重新上传图片");
            }
            if (av.length() > MAX_AVATAR_LENGTH) {
                throw new BizException(400, "头像图片过大，请换一张小一点的");
            }
        }
        db.setNickname(nick);
        db.setAvatar(av.isEmpty() ? null : av);
        db.setUpdatedAt(IdGen.now());
        userMapper.updateProfile(db.getId(), db.getNickname(), db.getAvatar(), db.getUpdatedAt());
        // 令牌里存的是登录时的用户快照，不同步的话页面上还是旧昵称
        tokenStore.refreshUser(db);
        return db;
    }

    /**
     * 修改自己的密码
     */
    public void changePassword(SysUser current, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException(400, "新密码至少 6 位");
        }
        SysUser db = userMapper.findById(current.getId());
        if (db == null) {
            throw new BizException(401, "用户不存在");
        }
        if (!encoder.matches(oldPassword == null ? "" : oldPassword, db.getPassword())) {
            throw new BizException(400, "原密码不正确");
        }
        db.setPassword(encoder.encode(newPassword));
        db.setUpdatedAt(IdGen.now());
        db.setNickname(db.getNickname());
        db.setRole(db.getRole());
        db.setStatus(db.getStatus());
        userMapper.update(db);
    }

    public String encode(String raw) {
        return encoder.encode(raw);
    }
}
