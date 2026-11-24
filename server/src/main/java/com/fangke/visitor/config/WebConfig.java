package com.fangke.visitor.config;

import com.fangke.visitor.security.AppAuthInterceptor;
import com.fangke.visitor.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册鉴权拦截器 + 开发期 CORS。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AppAuthInterceptor appAuthInterceptor;
    private final AppProperties props;

    public WebConfig(AuthInterceptor authInterceptor, AppAuthInterceptor appAuthInterceptor,
                     AppProperties props) {
        this.authInterceptor = authInterceptor;
        this.appAuthInterceptor = appAuthInterceptor;
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/admin/**", "/api/guard/**");
        registry.addInterceptor(appAuthInterceptor)
                .addPathPatterns("/api/app/**");
    }

    /** 前端独立部署（Vite dev server）时需要；生产建议同域部署或由网关统一处理。 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 精确来源与通配来源合并成 patterns：Spring 不允许 allowedOrigins 与 allowedOriginPatterns 同时设置，
        // 而 patterns 本身也接受精确地址，所以统一走 patterns（局域网演示时可配 http://192.168.*:*）
        java.util.List<String> patterns = new java.util.ArrayList<>(props.getCorsAllowedOrigins());
        patterns.addAll(props.getCorsAllowedOriginPatterns());
        registry.addMapping("/api/**")
                .allowedOriginPatterns(patterns.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Employee-Id", "X-Trace-Id")
                .maxAge(3600);
    }
}
