package com.goocidata.agent.config;

import com.goocidata.agent.entity.SysUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录令牌存储（内存版，重启失效）
 *
 * @author nzj
 */
@Component
public class TokenStore {

    private static final class Holder {
        final SysUser user;
        volatile long expireAt;

        Holder(SysUser user, long expireAt) {
            this.user = user;
            this.expireAt = expireAt;
        }
    }

    private final Map<String, Holder> tokens = new ConcurrentHashMap<>();

    @Value("${agent-hub.token-ttl-minutes:720}")
    private long ttlMinutes;

    public String issue(SysUser user) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, new Holder(user, System.currentTimeMillis() + ttlMinutes * 60_000));
        cleanUp();
        return token;
    }

    /**
     * 校验并滑动续期，返回用户（含最新角色信息）
     */
    public SysUser verify(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Holder holder = tokens.get(token);
        if (holder == null) {
            return null;
        }
        if (holder.expireAt < System.currentTimeMillis()) {
            tokens.remove(token);
            return null;
        }
        holder.expireAt = System.currentTimeMillis() + ttlMinutes * 60_000;
        return holder.user;
    }

    public void revoke(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    /**
     * 用户信息变更后刷新令牌里的快照
     */
    public void refreshUser(SysUser user) {
        tokens.forEach((k, v) -> {
            if (v.user.getId().equals(user.getId())) {
                v.user.setNickname(user.getNickname());
                v.user.setAvatar(user.getAvatar());
                v.user.setRole(user.getRole());
                v.user.setStatus(user.getStatus());
            }
        });
    }

    private void cleanUp() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> e.getValue().expireAt < now);
    }
}
