package com.rajarajeshwari.finance.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/api/**", "/photos/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/roles", "/api/auth/me", "/api/auth/branding", "/api/auth/icon");
    }
}
