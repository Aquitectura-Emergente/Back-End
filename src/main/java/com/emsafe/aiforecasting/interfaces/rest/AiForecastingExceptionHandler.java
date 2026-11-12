package com.emsafe.aiforecasting.interfaces.rest;

import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.shared.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Casos que {@code GlobalExceptionHandler} no cubre, acotado a los controllers de este contexto.
 * 404, validación de @Valid y 403 siguen en el handler global.
 */
@RestControllerAdvice(basePackageClasses = AiForecastingInboxController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiForecastingExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AiForecastingExceptionHandler.class);

    /** Regla de dominio → 422; conflicto de estado → 409. */
    @ExceptionHandler(ForecastingRuleException.class)
    public ResponseEntity<ApiResponse<Void>> rule(ForecastingRuleException ex) {
        HttpStatus status = ex.getKind() == ForecastingRuleException.Kind.CONFLICT
                ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(ApiResponse.error(ex.getMessage()));
    }

    /** Dos escrituras concurrentes sobre la misma sesión/evento: la segunda pierde. */
    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ApiResponse<Void>> concurrent(RuntimeException ex) {
        log.warn("Concurrent AI forecasting write rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Concurrent or conflicting forecasting operation; retry the event"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> malformed(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid request format"));
    }
}
