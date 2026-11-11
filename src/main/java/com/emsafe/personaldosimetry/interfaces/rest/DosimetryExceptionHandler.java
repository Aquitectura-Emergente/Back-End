package com.emsafe.personaldosimetry.interfaces.rest;

import com.emsafe.personaldosimetry.domain.model.DosimetryRuleException;
import com.emsafe.shared.dto.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses = PersonalDosimetryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DosimetryExceptionHandler {
    @ExceptionHandler(DosimetryRuleException.class)
    public ResponseEntity<ApiResponse<Void>> rule(DosimetryRuleException ex) {
        int status = ex.getKind() == DosimetryRuleException.Kind.CONFLICT ? 409 : 422;
        return ResponseEntity.status(status).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ApiResponse<Void>> conflict(RuntimeException ex) {
        return ResponseEntity.status(409).body(ApiResponse.error("Concurrent or conflicting dosimetry operation"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> malformed(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid request format"));
    }
}
