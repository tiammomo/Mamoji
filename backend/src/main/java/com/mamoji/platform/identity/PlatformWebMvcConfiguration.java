package com.mamoji.platform.identity;

import com.mamoji.platform.product.ProductModuleInterceptor;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

@Configuration
public class PlatformWebMvcConfiguration implements WebMvcConfigurer {
    private final CurrentActorArgumentResolver currentActorArgumentResolver;
    private final ProductModuleInterceptor productModuleInterceptor;

    public PlatformWebMvcConfiguration(
        CurrentActorArgumentResolver currentActorArgumentResolver,
        ProductModuleInterceptor productModuleInterceptor
    ) {
        this.currentActorArgumentResolver = currentActorArgumentResolver;
        this.productModuleInterceptor = productModuleInterceptor;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentActorArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(productModuleInterceptor).addPathPatterns("/api/**");
    }
}
