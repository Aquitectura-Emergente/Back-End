package com.emsafe.aiforecasting.application;

/**
 * Ámbito, por hilo, en el que las evaluaciones se redactan con la plantilla y nunca con el LLM. Lo usa
 * la semilla de demo al entregar los avisos del historial: son jornadas pasadas que nadie va a leer en
 * vivo, y llamar a Gemini por cada una alargaría el arranque y gastaría cuota. Las evaluaciones en vivo
 * (fuera del ámbito, p. ej. las que entrega el relay programado) siguen usando el LLM.
 *
 * <p>Funciona porque el inbox de AI Forecasting procesa los eventos de forma síncrona, en el mismo hilo
 * que drena el relay.</p>
 */
public final class TemplateOnlyRedaction {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private TemplateOnlyRedaction() {
    }

    /** Ejecuta {@code work} con la redacción por plantilla; restaura el estado anterior al terminar. */
    public static void run(Runnable work) {
        boolean previous = ACTIVE.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            if (previous) {
                ACTIVE.set(Boolean.TRUE);
            } else {
                ACTIVE.remove();
            }
        }
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }
}
