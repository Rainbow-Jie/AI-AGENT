package com.goocidata.agent.service;

import com.goocidata.agent.common.BizException;
import com.goocidata.agent.common.IdGen;
import com.goocidata.agent.config.TokenStore;
import com.goocidata.agent.entity.SysUser;
import com.goocidata.agent.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户管理
 *
 * @author nzj
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final AuthService authService;
    private final TokenStore tokenStore;

    public List<SysUser> list(String kw) {
        return userMapper.list(kw);
    }

    public SysUser create(SysUser input) {
        validate(input, true);
        if (userMapper.findByUsername(input.getUsername().trim()) != null) {
            throw new BizException("用户名已存在");
        }
        SysUser user = new SysUser();
        user.setId(IdGen.next());
        user.setUsername(input.getUsername().trim());
        user.setPassword(authService.encode(input.getPassword()));
        user.setNickname(emptyToNull(input.getNickname()) == null ? input.getUsername() : input.getNickname().trim());
        user.setRole(input.getRole() == null ? "USER" : input.getRole());
        user.setStatus(input.getStatus() == null ? 1 : input.getStatus());
        user.setCreatedAt(IdGen.now());
        user.setUpdatedAt(user.getCreatedAt());
        userMapper.insert(user);
        return user;
    }

    public SysUser update(SysUser input) {
        if (input.getId() == null) {
            throw new BizException(400, "缺少用户 ID");
        }
        SysUser db = userMapper.findById(input.getId());
        if (db == null) {
            throw new BizException("用户不存在");
        }
        db.setNickname(emptyToNull(input.getNickname()) == null ? db.getNickname() : input.getNickname().trim());
        db.setRole(input.getRole() == null ? db.getRole() : input.getRole());
        db.setStatus(input.getStatus() == null ? db.getStatus() : input.getStatus());
        if (input.getPassword() != null && !input.getPassword().isEmpty()) {
            if (input.getPassword().length() < 6) {
                throw new BizException(400, "密码至少 6 位");
            }
            db.setPassword(authService.encode(input.getPassword()));
        }
        db.setUpdatedAt(IdGen.now());
        userMapper.update(db);
        tokenStore.refreshUser(db);
        return db;
    }

    public void delete(String id, SysUser current) {
        SysUser db = userMapper.findById(id);
        if (db == null) {
            return;
        }
        if (db.getId().equals(current.getId())) {
            throw new BizException("不能删除当前登录账号");
        }
        if ("ADMIN".equals(db.getRole())) {
            List<SysUser> all = userMapper.list(null);
            long adminCount = all.stream().filter(u -> "ADMIN".equals(u.getRole())).count();
            if (adminCount <= 1) {
                throw new BizException("系统至少需要保留一个管理员");
            }
        }
        userMapper.delete(id);
    }

    private void validate(SysUser input, boolean needPassword) {
        if (input.getUsername() == null || input.getUsername().trim().length() < 3) {
            throw new BizException(400, "用户名至少 3 位");
        }
        if (!input.getUsername().trim().matches("[A-Za-z0-9_@.\\-]{3,32}")) {
            throw new BizException(400, "用户名仅支持字母、数字及 _ @ . -");
        }
        if (needPassword && (input.getPassword() == null || input.getPassword().length() < 6)) {
            throw new BizException(400, "密码至少 6 位");
        }
    }

    private String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }
}
