package com.mamoji.platform.product;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ProductModuleInterceptor implements HandlerInterceptor {
    private final ProductModuleCatalog modules;

    public ProductModuleInterceptor(ProductModuleCatalog modules) {
        this.modules = modules;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresProductModule requirement = AnnotatedElementUtils.findMergedAnnotation(
            handlerMethod.getMethod(),
            RequiresProductModule.class
        );
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getBeanType(),
                RequiresProductModule.class
            );
        }
        if (requirement != null && !modules.isEnabled(requirement.value())) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Product module is not enabled: " + requirement.value()
            );
        }
        return true;
    }
}
