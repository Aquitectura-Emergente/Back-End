package com.emsafe.devicemanagement.infrastructure.mqtt.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Ejemplos JSON copiados literalmente de la sección "Contrato MQTT" de los README de los Edges. */
public final class ContractFixtures {
    private ContractFixtures() {
    }

    public static String industrial(String name) {
        return read("/contracts/edge-industrial/" + name + ".json");
    }

    public static String home(String name) {
        return read("/contracts/edge-hogar/" + name + ".json");
    }

    public static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(String path) {
        try (InputStream in = ContractFixtures.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
