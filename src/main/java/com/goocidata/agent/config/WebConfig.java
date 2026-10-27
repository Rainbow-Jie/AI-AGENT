package com.goocidata.agent.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Web 配置：拦截器、跨域与静态资源缓存
 *
 * @author nzj
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /**
     * 允许跨域访问的来源，逗号分隔。
     * <p>
     * 本地开发默认 {@code *}；部署到服务器时前端与接口同源（都由 nginx 反代），
     * 所以生产配置里留空——留空即不开启 CORS，浏览器同源策略就够用了。
     * 真的要做前后端分离再填具体域名，不要在生产留 {@code *}：
     * 那等于允许任何网站带着用户令牌调用接口。
     */
    @Value("${agent-hub.security.cors-origins:*}")
    private String corsOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/init-status");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String raw = corsOrigins == null ? "" : corsOrigins.trim();
        if (raw.isEmpty()) {
            return;
        }
        String[] origins = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 静态资源显式声明「每次协商」。
     * <p>
     * 默认不返回 Cache-Control 时，浏览器会按 Last-Modified 做启发式缓存，
     * 结果就是改了 css / js 之后刷新还是旧样式，必须手动强刷才生效。
     * 这里改成 no-cache + 校验：文件没变走 304 不浪费带宽，变了立刻拿到新内容。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache().mustRevalidate());
    }
}
