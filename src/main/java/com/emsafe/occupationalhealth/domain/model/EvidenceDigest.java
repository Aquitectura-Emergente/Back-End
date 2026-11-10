package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Huella del contenido del reporte (informe §5.6.1).
 *
 * Es lo que hace demostrable que el PDF entregado se corresponde con el reporte
 * guardado: si alguien edita el archivo, el digest deja de coincidir. Se calcula
 * sobre el contenido canónico del reporte, no sobre el PDF, para que el mismo
 * reporte dé la misma huella aunque cambie el formato de exportación.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EvidenceDigest {

    public static final String SHA_256 = "SHA-256";

    @Column(name = "evidence_digest_algorithm", nullable = false, length = 20)
    private String algorithm;

    @Column(name = "evidence_digest_value", nullable = false, length = 128)
    private String value;

    public static EvidenceDigest of(String content) {
        return new EvidenceDigest(SHA_256, hash(content));
    }

    /** Si el contenido dado produce esta misma huella. */
    public boolean matches(String content) {
        return value != null && value.equals(hash(content));
    }

    private static String hash(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance(SHA_256)
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 es obligatorio en toda JVM; si falta, algo está muy roto.
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
