package com.emsafe.aiforecasting.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Nombres de los operarios para los textos que lee el SSO, resueltos en el backend. Nunca se envían
 * al LLM: solo se usan para escribir los textos finales. Sin nombre, el operario es
 * {@code "operario #id"}.
 */
public record WorkerNames(Map<Long, String> names) {

    public WorkerNames {
        names = names == null ? Map.of() : Map.copyOf(names);
    }

    public static WorkerNames none() {
        return new WorkerNames(Map.of());
    }

    public boolean known(Long workerId) {
        return names.containsKey(workerId);
    }

    /** Nombre del operario o {@code "operario #id"}. */
    public String label(Long workerId) {
        String name = names.get(workerId);
        return name != null ? name : "operario #" + workerId;
    }

    /**
     * El operario precedido de una preposición ({@code ""}, {@code "a"}, {@code "de"}, {@code "con"},
     * {@code "para"}…): "de Lucía Mendoza" o, sin nombre, "del operario #12".
     */
    public String with(String preposition, Long workerId) {
        if (known(workerId)) {
            return preposition.isEmpty() ? label(workerId) : preposition + " " + label(workerId);
        }
        return switch (preposition) {
            case "" -> "el " + label(workerId);
            case "de" -> "del " + label(workerId);
            case "a" -> "al " + label(workerId);
            default -> preposition + " el " + label(workerId);
        };
    }

    /** Varios operarios: la preposición va con el primero; "de Ana, Luis y el operario #15". */
    public String list(String preposition, List<Long> workerIds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < workerIds.size(); i++) {
            if (i > 0) sb.append(i == workerIds.size() - 1 ? " y " : ", ");
            sb.append(i == 0 ? with(preposition, workerIds.get(i)) : with("", workerIds.get(i)));
        }
        return sb.toString();
    }
}
