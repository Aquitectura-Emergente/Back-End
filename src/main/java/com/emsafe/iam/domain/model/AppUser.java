package com.emsafe.iam.domain.model;

import com.emsafe.shared.exception.BadRequestException;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 5)
    private String initials;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(length = 30)
    private String phone;

    @Column(length = 200)
    private String location;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = UserStatus.ACTIVE;

    @Column(length = 100)
    private String specialty;

    @Column(length = 100)
    private String department;

    private LocalDate joinDate;

    private LocalDateTime lastLogin;

    /** Registered address (human-readable) */
    @Column(length = 300)
    private String address;

    /** Geographic coordinates of the client's registered site */
    @Column
    private Double latitude;

    @Column
    private Double longitude;

    /** Free-text notes captured from the admin user edit forms. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    // ── Client (company / individual) profile fields ──────────────────────────
    @Column(name = "client_type", length = 20)
    private String clientType;

    @Column(name = "tax_id", length = 50)
    private String taxId;

    @Column(length = 50)
    private String industry;

    @Column(length = 100)
    private String country;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    @Column(name = "contact_email", length = 150)
    private String contactEmail;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    // ─── Comportamiento del agregado ──────────────────────────────────────────

    /** Cuenta habilitada. Distinto del estado de un dispositivo, pese al nombre. */
    public boolean isActive() {
        return UserStatus.ACTIVE.equalsIgnoreCase(status);
    }

    /** Alta hecha desde el móvil, a la espera de que un admin la apruebe. */
    public boolean isPending() {
        return UserStatus.PENDING.equalsIgnoreCase(status);
    }

    public boolean hasRole(Role expected) {
        return role == expected;
    }

    /**
     * Solo las cuentas activas pueden iniciar sesión. El motivo del rechazo se
     * distingue a propósito: "pendiente de aprobación" y "desactivada" son
     * situaciones distintas para el usuario, y ninguna de las dos debe
     * confundirse con una contraseña incorrecta.
     */
    public void assertCanSignIn() {
        if (status == null || isActive()) return;
        if (isPending()) {
            throw new BadRequestException("Your account is pending approval by an administrator.");
        }
        throw new BadRequestException(
                "Your account has been deactivated. Please contact an administrator.");
    }

    /** Sella el momento del último acceso correcto. */
    public void recordLogin() {
        this.lastLogin = LocalDateTime.now();
    }
}
