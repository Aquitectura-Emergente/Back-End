package com.emsafe.iam.domain.services;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MS = 15 * 60 * 1000L; // 15 minutos

    private record Attempt(int count, Instant lockedUntil) {}

    private final Map<String, Attempt> cache = new ConcurrentHashMap<>();

    public boolean isBlocked(String email) {
        Attempt a = cache.get(email.toLowerCase());
        if (a == null) return false;
        if (a.lockedUntil() != null && Instant.now().isBefore(a.lockedUntil())) return true;
        if (a.lockedUntil() != null) cache.remove(email.toLowerCase()); // lock expired
        return false;
    }

    public void registerFailure(String email) {
        String key = email.toLowerCase();
        Attempt current = cache.getOrDefault(key, new Attempt(0, null));
        int newCount = current.count() + 1;
        Instant lockUntil = newCount >= MAX_ATTEMPTS
                ? Instant.now().plusMillis(LOCK_DURATION_MS)
                : null;
        cache.put(key, new Attempt(newCount, lockUntil));
    }

    public void registerSuccess(String email) {
        cache.remove(email.toLowerCase());
    }

    public long secondsUntilUnlock(String email) {
        Attempt a = cache.get(email.toLowerCase());
        if (a == null || a.lockedUntil() == null) return 0;
        return Math.max(0, a.lockedUntil().getEpochSecond() - Instant.now().getEpochSecond());
    }
}
