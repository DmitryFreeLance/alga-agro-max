package ru.algaagro.maxapp.config;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/miniapp/assets/**")
                .addResourceLocations("classpath:/static/miniapp/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());

        registry.addResourceHandler("/miniapp/app.js", "/miniapp/app.css")
                .addResourceLocations("classpath:/static/miniapp/")
                .setCacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic());
    }
}
