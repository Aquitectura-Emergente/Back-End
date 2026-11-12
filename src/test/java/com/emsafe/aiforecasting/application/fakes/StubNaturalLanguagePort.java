package com.emsafe.aiforecasting.application.fakes;

import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.ports.NaturalLanguagePort;

import java.time.Clock;
import java.util.function.Function;

/** LLM falso: por defecto responde un texto fijo; se puede hacer fallar o devolver null. */
public class StubNaturalLanguagePort implements NaturalLanguagePort {

    public static final String LLM_TEXT = "Texto redactado por el LLM";

    private final Clock clock;
    private Function<PreventiveAssessment, NaturalLanguageRedaction> behaviour;
    private int calls;

    public StubNaturalLanguagePort(Clock clock) {
        this.clock = clock;
        respondWithText();
    }

    public void respondWithText() {
        behaviour = a -> new NaturalLanguageRedaction(LLM_TEXT, RedactionSource.LLM, clock.instant());
    }

    public void fail() {
        behaviour = a -> {
            throw new IllegalStateException("LLM timeout");
        };
    }

    public void returnNull() {
        behaviour = a -> null;
    }

    @Override
    public NaturalLanguageRedaction redact(PreventiveAssessment assessment) {
        calls++;
        return behaviour.apply(assessment);
    }

    public int calls() {
        return calls;
    }
}
