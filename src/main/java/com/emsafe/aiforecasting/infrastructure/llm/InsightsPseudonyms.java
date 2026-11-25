package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.PositionStats;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.ProjectedCritical;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.PositionRef;
import com.emsafe.aiforecasting.domain.model.WorkerNames;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seudónimos del análisis del periodo: "Operario A", "Operario B"… y "Puesto A"… en lugar de los
 * IDs y nombres reales, que nunca salen del backend. Se asignan en el orden del resumen (por
 * operario, luego los de las proyecciones; puestos por índice promedio) y valen para una sola
 * llamada. Al volver, {@link #restore(String, WorkerNames)} los reemplaza en el texto por el nombre
 * del operario (resuelto en el backend; sin nombre, "operario #id") y el nombre (o ID) del puesto; un
 * seudónimo desconocido invalida la respuesta. Los nombres nunca forman parte del prompt.
 */
final class InsightsPseudonyms {

    static final String WORKER = "Operario";
    static final String POSITION = "Puesto";
    /** "Operario A", "al operario AB", "del Puesto C"… (las letras en mayúscula, 1 a 3), con su artículo. */
    private static final Pattern ALIAS = Pattern.compile(
            "(?:\\b([Ee]l|[Aa]l|[Dd]el)\\s+)?\\b([Oo]perario|[Pp]uesto)\\s+([A-Z]{1,3})\\b");

    private final Map<Long, String> workerAlias = new LinkedHashMap<>();
    private final Map<String, Long> workerByLetters = new HashMap<>();
    private final Map<Long, String> positionAlias = new LinkedHashMap<>();
    private final Map<String, PositionRef> positionByLetters = new HashMap<>();

    InsightsPseudonyms(AnalyticsSummary s) {
        s.perWorker().forEach(w -> addWorker(w.workerId()));
        s.workersAboveWarning().forEach(w -> addWorker(w.workerId()));
        for (ProjectedCritical p : s.projectedCriticalWorkers()) {
            addWorker(p.workerId());
            addWorker(p.targetWorkerId());
        }
        for (PositionStats p : s.topPositions()) {
            if (p.positionId() != null && !positionAlias.containsKey(p.positionId())) {
                String letters = letters(positionAlias.size());
                positionAlias.put(p.positionId(), POSITION + " " + letters);
                positionByLetters.put(letters, new PositionRef(p.positionId(), p.positionName()));
            }
        }
    }

    private void addWorker(Long id) {
        if (id != null && !workerAlias.containsKey(id)) {
            String letters = letters(workerAlias.size());
            workerAlias.put(id, WORKER + " " + letters);
            workerByLetters.put(letters, id);
        }
    }

    /** 0 → A, 25 → Z, 26 → AA… */
    static String letters(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    String worker(Long id) {
        return workerAlias.get(id);
    }

    String position(Long id) {
        return positionAlias.get(id);
    }

    /** "Operario A" o "A" → ID real. */
    Long resolveWorker(String alias) {
        Long id = workerByLetters.get(strip(alias, WORKER));
        if (id == null) {
            throw new InvalidInsightsResponseException("unknown worker pseudonym");
        }
        return id;
    }

    /** "Puesto A" o "A" → puesto real. */
    PositionRef resolvePosition(String alias) {
        PositionRef ref = positionByLetters.get(strip(alias, POSITION));
        if (ref == null) {
            throw new InvalidInsightsResponseException("unknown position pseudonym");
        }
        return ref;
    }

    /**
     * Reemplaza cada seudónimo del texto: "al Operario A" pasa a "a Lucía Mendoza" (sin nombre, "al
     * operario #14") y "el Puesto A" a "el Puesto Prensa hidráulica" (sin nombre, "el Puesto 31").
     */
    String restore(String text, WorkerNames names) {
        Matcher m = ALIAS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String article = m.group(1);
            String prefix = m.group(2);
            String replacement;
            if (prefix.equalsIgnoreCase(WORKER)) {
                Long id = resolveWorker(m.group(3));
                if (names.known(id)) {
                    replacement = withoutDefiniteArticle(article) + names.label(id);
                } else {
                    replacement = (article == null ? "" : article + " ") + prefix + " #" + id;
                }
            } else {
                PositionRef p = resolvePosition(m.group(3));
                replacement = (article == null ? "" : article + " ") + prefix + " "
                        + (p.positionName() == null ? p.positionId() : p.positionName());
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Ante un nombre propio: "el" desaparece, "al" pasa a "a" y "del" a "de". */
    private static String withoutDefiniteArticle(String article) {
        if (article == null) return "";
        boolean upper = Character.isUpperCase(article.charAt(0));
        return switch (article.toLowerCase(Locale.ROOT)) {
            case "al" -> upper ? "A " : "a ";
            case "del" -> upper ? "De " : "de ";
            default -> "";
        };
    }

    String workerLetters() {
        return String.join(", ", workerAlias.values());
    }

    String positionLetters() {
        return String.join(", ", positionAlias.values());
    }

    private static String strip(String alias, String prefix) {
        if (alias == null) {
            throw new InvalidInsightsResponseException("empty pseudonym");
        }
        String value = alias.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            value = value.substring(prefix.length()).trim();
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
