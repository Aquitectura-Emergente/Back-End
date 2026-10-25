package com.emsafe.iam.domain.model;

/**
 * Iniciales mostradas en el avatar de un usuario.
 *
 * Existía duplicada y, peor, con dos algoritmos distintos: AuthService (alta
 * desde el móvil) tomaba la inicial de las dos primeras palabras, y UserService
 * (alta desde el portal) la de la primera y la última. "Juan Carlos Perez"
 * salía como JC por un camino y JP por el otro, para la misma persona.
 *
 * Se unifica en primera + última, que es la convención habitual y la que ya
 * usaba el alta del portal (el camino por el que entra la mayoría).
 */
public final class Initials {

    private static final String UNKNOWN = "??";

    private Initials() {
    }

    public static String from(String name) {
        if (name == null || name.isBlank()) return UNKNOWN;
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (String.valueOf(parts[0].charAt(0))
                + parts[parts.length - 1].charAt(0)).toUpperCase();
    }
}
