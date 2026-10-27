package com.goocidata.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goocidata.agent.common.R;
import com.goocidata.agent.entity.SysUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录与权限拦截器
 *
 * @author nzj
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER = "currentUser";

    private final TokenStore tokenStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 是否允许把令牌放在 URL 上（{@code ?token=xxx}）。
     * <p>
     * 这个口子只给本地调试用：令牌一旦进 URL，就会被 nginx 访问日志、
     * 浏览器历史、Referer 头原样带走。公网部署必须关掉。
     */
    @Value("${agent-hub.security.allow-token-param:true}")
    private boolean allowTokenParam;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 先剥掉 context-path 再判断：应用一旦被挂到子路径（例如 /AI），
        // getRequestURI() 会变成 /AI/api/xxx，直接 startsWith("/api/") 会判假而静默放行，
        // 等于鉴权整体失效。这里统一换算成「应用内路径」再比较。
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        if (!uri.startsWith("/api/")) {
            return true;
        }
        // 跨域预检直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = resolveToken(request);
        SysUser user = tokenStore.verify(token);

        if (user == null) {
            write(response, R.fail(401, "登录已失效，请重新登录"));
            return false;
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            write(response, R.fail(403, "账号已被停用"));
            return false;
        }
        // 管理端接口需要管理员角色
        if (uri.startsWith("/api/admin/") && !"ADMIN".equals(user.getRole())) {
            write(response, R.fail(403, "无权限访问，仅管理员可操作"));
            return false;
        }
        request.setAttribute(ATTR_USER, user);
        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        String token = request.getHeader("X-Token");
        if (token != null && !token.isEmpty()) {
            return token;
        }
        if (allowTokenParam) {
            return request.getParameter("token");
        }
        return null;
    }

    private void write(HttpServletResponse response, R<?> body) throws Exception {
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
