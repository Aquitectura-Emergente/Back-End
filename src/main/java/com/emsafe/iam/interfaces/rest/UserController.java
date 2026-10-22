package com.emsafe.iam.interfaces.rest;

import com.emsafe.shared.dto.ApiResponse;
import com.emsafe.iam.interfaces.rest.dto.ChangePasswordRequest;
import com.emsafe.iam.interfaces.rest.dto.CreateUserRequest;
import com.emsafe.iam.interfaces.rest.dto.UpdateUserRequest;
import com.emsafe.iam.interfaces.rest.dto.UserDto;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.iam.application.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<UserDto>>> getAll(
            @RequestParam(required = false) String role) {
        return ResponseEntity.ok(ApiResponse.ok(userService.findAll(role)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserDto>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserDto>> create(@Valid @RequestBody CreateUserRequest req) {
        UserDto created = userService.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("User created", created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserDto>> update(
            @PathVariable Long id,
            @RequestBody UpdateUserRequest req,
            Authentication auth) {
        assertSelfOrAdmin(auth, id);
        return ResponseEntity.ok(ApiResponse.ok(userService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("User deleted", null));
    }

    @PatchMapping("/{id}/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @PathVariable Long id,
            @Valid @RequestBody ChangePasswordRequest req,
            Authentication auth) {
        assertSelfOrAdmin(auth, id);
        userService.changePassword(id, req);
        return ResponseEntity.ok(ApiResponse.ok("Password updated", null));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserDto>> getProfile(Authentication auth) {
        AppUser user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new com.emsafe.shared.exception.ResourceNotFoundException("User", 0L));
        return ResponseEntity.ok(ApiResponse.ok(UserDto.from(user)));
    }

    /**
     * Non-admin callers (technicians) may only modify their own account.
     * PUT /api/users/{id} and PATCH /api/users/{id}/password are open to
     * TECHNICIAN in SecurityConfig for the "own profile" flow — without this
     * check a technician could edit any user, including the admin's password.
     */
    private void assertSelfOrAdmin(Authentication auth, Long targetId) {
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) return;
        AppUser current = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (!current.getId().equals(targetId)) {
            throw new AccessDeniedException("You can only modify your own account");
        }
    }
}
