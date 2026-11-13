package com.emsafe.personaldosimetry.interfaces.rest;

import com.emsafe.personaldosimetry.application.DosimetryAuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class DosimetryAccessAuditFilter extends OncePerRequestFilter {
    private static final String BASE = "/api/v1/personal-dosimetry";
    private final DosimetryAuditService audit;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // MockMvc can leave servletPath empty; requestURI still excludes the query string.
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals(BASE) && !path.startsWith(BASE + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String principal = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                ? auth.getName() : null;
        try {
            chain.doFilter(request, response);
        } finally {
            if (response.getStatus() == 401 || response.getStatus() == 403) {
                try {
                    audit.recordDenied(principal, request.getMethod(), request.getRequestURI(), response.getStatus());
                } catch (RuntimeException ex) {
                    // Preserve the denial if storage is unavailable; expose the audit gap operationally.
                    log.error("Could not persist a denied personal-dosimetry access", ex);
                }
            }
        }
    }
}
