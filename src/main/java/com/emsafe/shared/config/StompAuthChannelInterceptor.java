package com.emsafe.shared.config;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.iam.infrastructure.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autenticación del WebSocket: el CONNECT de STOMP lleva {@code Authorization: Bearer <jwt>}
 * y cada SUBSCRIBE solo se acepta para los tópicos del propio usuario.
 *
 * <ul>
 *   <li>{@code /topic/clients/{id}/readings} y {@code /topic/workers/{id}/alerts}: el usuario {@code id}.</li>
 *   <li>{@code /topic/organizations/{id}/alerts}: el SSO de esa organización.</li>
 *   <li>{@code /topic/readings} y {@code /topic/discovery}: administrador y técnico.</li>
 * </ul>
 * El administrador puede suscribirse a cualquier tópico.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {
    private static final Pattern OWN_TOPIC = Pattern.compile("/topic/(clients|workers)/(\\d+)/(readings|alerts)");
    private static final Pattern ORGANIZATION_TOPIC = Pattern.compile("/topic/organizations/(\\d+)/alerts");
    private static final List<String> STAFF_TOPICS = List.of("/topic/readings", "/topic/discovery");

    private final JwtUtil jwt;
    private final UserRepository users;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null || stomp.getCommand() == null) return message;
        if (stomp.getCommand() == StompCommand.CONNECT) {
            AppUser user = authenticate(stomp.getFirstNativeHeader("Authorization"));
            stomp.setUser(new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
        } else if (stomp.getCommand() == StompCommand.SUBSCRIBE) {
            AppUser user = current(stomp.getUser());
            if (!maySubscribe(user, stomp.getDestination())) {
                throw new MessageDeliveryException("Subscription not allowed: " + stomp.getDestination());
            }
        } else if (stomp.getCommand() == StompCommand.SEND) {
            current(stomp.getUser());
        }
        return message;
    }

    private AppUser authenticate(String header) {
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            throw new MessageDeliveryException("Missing bearer token");
        }
        String token = header.substring(7);
        if (!jwt.isTokenValid(token)) throw new MessageDeliveryException("Invalid token");
        AppUser user = users.findByEmail(jwt.extractEmail(token))
                .orElseThrow(() -> new MessageDeliveryException("Unknown account"));
        if (!user.isActive()) throw new MessageDeliveryException("Inactive account");
        return user;
    }

    private AppUser current(Principal principal) {
        if (principal == null) throw new MessageDeliveryException("Not authenticated");
        return users.findByEmail(principal.getName())
                .orElseThrow(() -> new MessageDeliveryException("Unknown account"));
    }

    static boolean maySubscribe(AppUser user, String destination) {
        if (destination == null) return false;
        if (user.hasRole(Role.ADMIN)) return true;
        if (STAFF_TOPICS.contains(destination)) return user.hasRole(Role.TECHNICIAN);
        Matcher own = OWN_TOPIC.matcher(destination);
        if (own.matches()) {
            boolean kindMatches = own.group(1).equals("clients") == own.group(3).equals("readings");
            return kindMatches && String.valueOf(user.getId()).equals(own.group(2));
        }
        Matcher organization = ORGANIZATION_TOPIC.matcher(destination);
        if (organization.matches()) {
            return user.hasRole(Role.SSO_RESPONSIBLE) && user.getOrganizationId() != null
                    && String.valueOf(user.getOrganizationId()).equals(organization.group(1));
        }
        return false;
    }
}
