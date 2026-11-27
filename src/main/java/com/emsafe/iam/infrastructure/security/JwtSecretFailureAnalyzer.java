package com.emsafe.iam.infrastructure.security;

import com.emsafe.iam.infrastructure.security.JwtSecretValidator.InvalidJwtSecretException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Convierte un {@link InvalidJwtSecretException} en el recuadro "APPLICATION FAILED TO START"
 * de Spring Boot, con la causa y qué hacer, en vez de una traza larga.
 * Registrado en META-INF/spring.factories.
 */
public class JwtSecretFailureAnalyzer extends AbstractFailureAnalyzer<InvalidJwtSecretException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidJwtSecretException cause) {
        String description = "Secreto JWT inválido para los perfiles prod/demo: " + cause.getMessage();
        String action = "Define la variable de entorno JWT_SECRET con un valor Base64 propio de al menos "
                + JwtSecretValidator.MIN_LENGTH + " caracteres que decodifique a "
                + JwtSecretValidator.MIN_KEY_BYTES + " bytes o más (por ejemplo: openssl rand -base64 48). "
                + "No reutilices el valor de application-dev.properties.";
        return new FailureAnalysis(description, action, cause);
    }
}
