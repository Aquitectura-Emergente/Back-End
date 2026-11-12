package com.emsafe.shared.config;

import com.emsafe.iam.infrastructure.security.JwtAuthFilter;
import com.emsafe.personaldosimetry.application.DosimetryAuditService;
import com.emsafe.personaldosimetry.interfaces.rest.DosimetryAccessAuditFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;
    private final CorsConfigurationSource corsConfigurationSource;
    private final DosimetryAuditService dosimetryAudit;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Ojo con el orden y con el número de mapeos: ExceptionHandlingConfigurer solo
                // construye un DelegatingAuthenticationEntryPoint si hay DOS o más. Con uno solo
                // lo aplica a todas las peticiones e ignora el matcher, así que el 401 de
                // dosimetría se llevaba por delante el 403 que devuelve el resto de la API.
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new AntPathRequestMatcher("/api/v1/personal-dosimetry/**"))
                        .defaultAuthenticationEntryPointFor(
                                new Http403ForbiddenEntryPoint(),
                                AnyRequestMatcher.INSTANCE))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login", "/api/auth/refresh", "/api/auth/register").permitAll()
                        // Swagger / OpenAPI UI
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**"
                        ).permitAll()
                        // AI Forecasting: requiere JWT (debe ir antes del permitAll de /api/v1/**)
                        .requestMatchers("/api/v1/ai-forecasting/**").authenticated()
                        // IoT telemetry (edge ingestion + web/mobile consumption).
                        // Público por ahora: el edge no tiene JWT. Endurecer luego
                        // (API key en el POST, JWT en el GET) si se requiere.
                        .requestMatchers("/api/v1/personal-dosimetry/**").authenticated()
                        // Occupational Health & Mitigation (segmento industrial).
                        // DEBE declararse ANTES del permitAll de /api/v1/**: comparte
                        // ese prefijo y, sin esta regla, aprobar una medida de
                        // mitigación quedaría abierto a cualquiera sin autenticar.
                        // El filtrado fino (el SSO decide, el operario solo ve lo suyo)
                        // lo hace MitigationAccessPolicy; aquí solo se cierra la puerta.
                        .requestMatchers("/api/v1/occupational-health/**")
                                .hasAnyRole("SSO_RESPONSIBLE", "WORKER", "ADMIN")
                        .requestMatchers("/api/v1/**").permitAll()
                        // WebSocket handshake (STOMP) — real-time telemetry push (no JWT, like /api/v1).
                        .requestMatchers("/ws/**").permitAll()
                        // Tech endpoints — accessible by TECHNICIAN and ADMIN
                        .requestMatchers("/api/tech/**").hasAnyRole("TECHNICIAN", "ADMIN")
                        // Client (mobile app) endpoints — accessible by CLIENT and ADMIN
                        .requestMatchers("/api/client/**").hasAnyRole("CLIENT", "ADMIN")
                        // SSO panel — solo el responsable de SSO (el ADMIN NO hereda ni se mezcla aquí)
                        .requestMatchers("/api/sso/**").hasRole("SSO_RESPONSIBLE")
                        // Profile endpoints — own account (UserController.assertSelfOrAdmin limita a uno mismo)
                        .requestMatchers(HttpMethod.GET,   "/api/users/profile")
                        .hasAnyRole("TECHNICIAN", "ADMIN", "WORKER", "SSO_RESPONSIBLE")
                        .requestMatchers(HttpMethod.PUT,   "/api/users/*")
                        .hasAnyRole("TECHNICIAN", "ADMIN", "WORKER", "SSO_RESPONSIBLE")
                        .requestMatchers(HttpMethod.PATCH, "/api/users/*/password")
                        .hasAnyRole("TECHNICIAN", "ADMIN", "WORKER", "SSO_RESPONSIBLE")
                        // All other API endpoints — ADMIN only
                        .anyRequest().hasRole("ADMIN")
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new DosimetryAccessAuditFilter(dosimetryAudit), JwtAuthFilter.class)
                .build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
