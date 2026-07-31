package com.ociworker.config;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Resource
    private AuthInterceptor authInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 管理面板与 WebSSH 仅允许同源访问。仅 OpenAI 兼容接口允许浏览器跨域，
        // 且不接收 Cookie 凭据，避免任意站点读取面板登录态。
        registry.addMapping("/v1/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**", "/webssh", "/webssh/**", "/webssh-api/**")
                .excludePathPatterns("/api/auth/login");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/webssh/**")
                .addResourceLocations("classpath:/static/webssh/");
        // 带内容 hash 的构建产物可长期缓存；index.html 等入口文件必须每次协商，
        // 否则更新后浏览器仍用旧页面引用已不存在的 chunk（懒加载 404）。
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/dist/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/dist/")
                .setCacheControl(CacheControl.noCache());
    }
}
