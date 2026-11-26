package com.emsafe.aiforecasting.domain.model;

/**
 * El proveedor del LLM rechazó la llamada por cuota agotada (HTTP 429). Quien la recibe usa el
 * fallback; el análisis del periodo además lo guarda en caché por poco tiempo, para volver a probar
 * pronto sin gastar llamadas en cada clic. El mensaje nunca incluye la clave.
 */
public class LlmQuotaExceededException extends RuntimeException {

    private final String model;

    public LlmQuotaExceededException(String model) {
        super("LLM quota exceeded (HTTP 429) for model " + model);
        this.model = model;
    }

    public String getModel() {
        return model;
    }
}
