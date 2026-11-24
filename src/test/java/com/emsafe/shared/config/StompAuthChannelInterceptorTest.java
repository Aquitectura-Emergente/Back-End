package com.emsafe.shared.config;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.iam.infrastructure.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StompAuthChannelInterceptorTest {
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final UserRepository users = mock(UserRepository.class);
    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwt, users);

    @Test
    void connectNeedsAValidBearerToken() {
        AppUser client = user(5L, Role.CLIENT, null);
        when(jwt.isTokenValid("good")).thenReturn(true);
        when(jwt.extractEmail("good")).thenReturn(client.getEmail());

        assertThatThrownBy(() -> interceptor.preSend(connect(null), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(connect("Bearer bad"), null))
                .isInstanceOf(MessageDeliveryException.class);
        Message<?> accepted = interceptor.preSend(connect("Bearer good"), null);
        assertThat(StompHeaderAccessor.wrap(accepted).getUser().getName()).isEqualTo(client.getEmail());
    }

    @Test
    void eachUserSubscribesOnlyToItsOwnTopics() {
        AppUser client = user(5L, Role.CLIENT, null);
        AppUser worker = user(8L, Role.WORKER, 3L);
        AppUser sso = user(9L, Role.SSO_RESPONSIBLE, 3L);
        AppUser tech = user(10L, Role.TECHNICIAN, null);
        AppUser admin = user(1L, Role.ADMIN, null);

        assertThat(StompAuthChannelInterceptor.maySubscribe(client, "/topic/clients/5/readings")).isTrue();
        assertThat(StompAuthChannelInterceptor.maySubscribe(client, "/topic/clients/6/readings")).isFalse();
        assertThat(StompAuthChannelInterceptor.maySubscribe(client, "/topic/readings")).isFalse();
        assertThat(StompAuthChannelInterceptor.maySubscribe(worker, "/topic/workers/8/alerts")).isTrue();
        assertThat(StompAuthChannelInterceptor.maySubscribe(worker, "/topic/workers/8/readings")).isFalse();
        assertThat(StompAuthChannelInterceptor.maySubscribe(worker, "/topic/organizations/3/alerts")).isFalse();
        assertThat(StompAuthChannelInterceptor.maySubscribe(sso, "/topic/organizations/3/alerts")).isTrue();
        assertThat(StompAuthChannelInterceptor.maySubscribe(sso, "/topic/organizations/4/alerts")).isFalse();
        assertThat(StompAuthChannelInterceptor.maySubscribe(tech, "/topic/discovery")).isTrue();
        assertThat(StompAuthChannelInterceptor.maySubscribe(admin, "/topic/clients/5/readings")).isTrue();
    }

    @Test
    void aSubscriptionWithoutSessionOrOutsideScopeIsRefused() {
        AppUser client = user(5L, Role.CLIENT, null);
        when(users.findByEmail(client.getEmail())).thenReturn(Optional.of(client));

        assertThatThrownBy(() -> interceptor.preSend(subscribe(null, "/topic/clients/5/readings"), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(subscribe(client, "/topic/readings"), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThat(interceptor.preSend(subscribe(client, "/topic/clients/5/readings"), null)).isNotNull();
    }

    private AppUser user(Long id, Role role, Long organizationId) {
        AppUser user = AppUser.builder().id(id).email("u" + id + "@test.local").passwordHash("x").name("U")
                .initials("U").role(role).status("active").organizationId(organizationId).build();
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        return user;
    }

    private static Message<byte[]> connect(String authorization) {
        StompHeaderAccessor stomp = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorization != null) stomp.setNativeHeader("Authorization", authorization);
        stomp.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], stomp.getMessageHeaders());
    }

    private static Message<byte[]> subscribe(AppUser user, String destination) {
        StompHeaderAccessor stomp = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        stomp.setDestination(destination);
        if (user != null) stomp.setUser(new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
        stomp.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], stomp.getMessageHeaders());
    }
}
