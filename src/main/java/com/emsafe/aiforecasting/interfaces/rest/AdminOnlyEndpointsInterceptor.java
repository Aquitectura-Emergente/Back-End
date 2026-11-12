package com.emsafe.aiforecasting.interfaces.rest;

import com.emsafe.aiforecasting.application.ForecastingAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Exige ADMIN en inbox y outbox antes de que Spring MVC lea y valide el cuerpo.
 *
 * <p>{@code @PreAuthorize} en el controller no basta para "403 antes que 400": la lectura del
 * JSON y {@code @Valid} ocurren al resolver los argumentos, antes de invocar el proxy
 * asegurado. {@code preHandle} corre antes de esa resolución. La regla sigue en
 * {@link ForecastingAccessPolicy}; aquí solo se aplica antes.</p>
 */
public class AdminOnlyEndpointsInterceptor implements HandlerInterceptor {

    static final String[] PATHS = {"/api/v1/ai-forecasting/inbox/**", "/api/v1/ai-forecasting/outbox/**"};

    private final ForecastingAccessPolicy access;

    public AdminOnlyEndpointsInterceptor(ForecastingAccessPolicy access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        access.requireAdministrator(access.actor(SecurityContextHolder.getContext().getAuthentication()));
        return true;
    }
}
