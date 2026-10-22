package com.emsafe.shared.config;

import com.emsafe.iam.infrastructure.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
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
                        // IoT telemetry (edge ingestion + web/mobile consumption).
                        // Público por ahora: el edge no tiene JWT. Endurecer luego
                        // (API key en el POST, JWT en el GET) si se requiere.
                        .requestMatchers("/api/v1/**").permitAll()
                        // WebSocket handshake (STOMP) — real-time telemetry push (no JWT, like /api/v1).
                        .requestMatchers("/ws/**").permitAll()
                        // Tech endpoints — accessible by TECHNICIAN and ADMIN
                        .requestMatchers("/api/tech/**").hasAnyRole("TECHNICIAN", "ADMIN")
                        // Client (mobile app) endpoints — accessible by CLIENT and ADMIN
                        .requestMatchers("/api/client/**").hasAnyRole("CLIENT", "ADMIN")
                        // Profile endpoints — accessible by TECHNICIAN and ADMIN (own account)
                        .requestMatchers(HttpMethod.GET,   "/api/users/profile").hasAnyRole("TECHNICIAN", "ADMIN")
                        .requestMatchers(HttpMethod.PUT,   "/api/users/*").hasAnyRole("TECHNICIAN", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/users/*/password").hasAnyRole("TECHNICIAN", "ADMIN")
                        // All other API endpoints — ADMIN only
                        .anyRequest().hasRole("ADMIN")
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
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
