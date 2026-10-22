package com.emsafe.iam.application;

import com.emsafe.iam.interfaces.rest.dto.LoginRequest;
import com.emsafe.iam.interfaces.rest.dto.LoginResponse;
import com.emsafe.iam.interfaces.rest.dto.RefreshResponse;
import com.emsafe.iam.interfaces.rest.dto.RegisterRequest;
import com.emsafe.iam.infrastructure.security.JwtUtil;
import com.emsafe.iam.domain.services.LoginAttemptService;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Initials;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.domain.model.UserStatus;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginAttemptService loginAttemptService;

    public RefreshResponse refresh(String bearerToken) {
        String token = bearerToken.replace("Bearer ", "").trim();
        if (!jwtUtil.isTokenValid(token)) {
            throw new BadCredentialsException("Token is invalid or expired");
        }
        // Re-read the account from the DB: a deleted or deactivated user (or one
        // whose role changed) must not be able to renew a token indefinitely.
        String email = jwtUtil.extractEmail(token);
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Account no longer exists"));
        if (!user.isActive()) {
            throw new BadCredentialsException("Account is not active");
        }
        String newToken = jwtUtil.generateToken(user.getEmail(), user.getRole().name(), user.getId());
        return new RefreshResponse(newToken, jwtUtil.getExpirationMs());
    }

    @Transactional
    public LoginResponse login(LoginRequest req) {
        if (loginAttemptService.isBlocked(req.email())) {
            long secs = loginAttemptService.secondsUntilUnlock(req.email());
            // LockedException (not BadCredentialsException) so GlobalExceptionHandler
            // surfaces the real lockout message instead of "Invalid email or password".
            throw new LockedException("Account temporarily locked. Try again in " + secs + " seconds.");
        }

        try {
            AppUser user = userRepository.findByEmail(req.email())
                    .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

            if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
                throw new BadCredentialsException("Invalid email or password");
            }

            // Solo las cuentas activas entran: el auto-registro nace "pending" y
            // un admin puede desactivar cualquier cuenta. El agregado decide.
            user.assertCanSignIn();

            user.recordLogin();
            userRepository.save(user);

            String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name(), user.getId());

            Long technicianId = user.getRole() == Role.TECHNICIAN ? user.getId() : null;

            loginAttemptService.registerSuccess(req.email());

            return new LoginResponse(
                    token,
                    user.getEmail(),
                    user.getName(),
                    user.getInitials(),
                    user.getRole().name().toLowerCase(),
                    user.getId(),
                    technicianId
            );
        } catch (BadCredentialsException ex) {
            loginAttemptService.registerFailure(req.email());
            throw ex;
        }
    }

    /**
     * Public sign-up from the mobile app: creates a CLIENT in "pending" state.
     * The account cannot log in until an admin activates it from the web portal.
     */
    @Transactional
    public void register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new BadRequestException("Email already in use: " + req.email());
        }
        userRepository.save(AppUser.builder()
                .name(req.name().trim())
                .initials(Initials.from(req.name()))
                .email(req.email().trim())
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(Role.CLIENT)
                .phone(req.phone())
                .address(req.address())
                // Persist the client profile chosen at sign-up so the admin sees the
                // right type (company/individual) and contact when editing.
                .clientType(req.clientType())
                .contactName(req.contactName())
                .contactEmail(req.email().trim())
                .contactPhone(req.phone())
                .taxId(req.taxId())
                .industry(req.industry())
                .joinDate(LocalDate.now())
                .status(UserStatus.PENDING)
                .build());
    }
}
