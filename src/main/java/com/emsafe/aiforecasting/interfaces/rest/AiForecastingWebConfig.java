package com.emsafe.aiforecasting.interfaces.rest;

import com.emsafe.aiforecasting.application.ForecastingAccessPolicy;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registra {@link AdminOnlyEndpointsInterceptor} solo para las rutas de este contexto. */
@Configuration
public class AiForecastingWebConfig implements WebMvcConfigurer {

    private final ForecastingAccessPolicy access;

    public AiForecastingWebConfig(ForecastingAccessPolicy access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminOnlyEndpointsInterceptor(access))
                .addPathPatterns(AdminOnlyEndpointsInterceptor.PATHS);
    }
}
