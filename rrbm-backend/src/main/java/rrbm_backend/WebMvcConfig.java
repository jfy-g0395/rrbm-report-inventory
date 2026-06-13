package rrbm_backend;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PageAccessInterceptor pageAccessInterceptor;

    public WebMvcConfig(PageAccessInterceptor pageAccessInterceptor) {
        this.pageAccessInterceptor = pageAccessInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(pageAccessInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/**",
                        "/api/settings",
                        "/api/settings/**",
                        "/api/master-keys/**",
                        "/api/users/*/change-password",
                        "/actuator/**"
                );
    }
}
