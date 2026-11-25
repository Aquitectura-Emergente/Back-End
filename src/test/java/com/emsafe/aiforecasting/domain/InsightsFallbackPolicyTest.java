package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.PeriodInsights;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.Finding;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.FindingLevel;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.InsightPriority;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.PositionRef;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.Recommendation;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.WorkerNames;
import com.emsafe.aiforecasting.domain.services.InsightsFallbackPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.emsafe.aiforecasting.domain.InsightsFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class InsightsFallbackPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-22T15:00:00Z");
    private final InsightsFallbackPolicy policy = new InsightsFallbackPolicy();

    @Test
    void richPeriodProducesTheFullStructureFromTheSummaryFigures() {
        PeriodInsights i = policy.analyze(rich(), NOW);

        assertThat(i.generatedBy()).isEqualTo(RedactionSource.FALLBACK);
        assertThat(i.model()).isNull();
        assertThat(i.generatedAt()).isEqualTo(NOW);
        assertThat(i.organizationId()).isEqualTo(ORG);
        assertThat(i.from()).isEqualTo(FROM);
        assertThat(i.to()).isEqualTo(TO);
        assertThat(i.zone()).isEqualTo("America/Lima");

        assertThat(i.summary())
                .contains("2026-11-02").contains("2026-11-22")
                .contains("30 jornadas de 3 operarios")
                .contains("3 (10.00 %)").contains("90.00 % del límite").contains("1 (3.33 %)")
                .contains("2 operarios con proyección HIGH o CRITICAL").contains("1 CRITICAL");

        assertThat(i.findings()).extracting(Finding::level).containsExactly(
                FindingLevel.CRITICO, FindingLevel.CRITICO,
                FindingLevel.ATENCION, FindingLevel.ATENCION, FindingLevel.ATENCION, FindingLevel.ATENCION,
                FindingLevel.INFO);
        List<String> texts = i.findings().stream().map(Finding::text).toList();
        assertThat(texts.get(0)).contains("1 jornada superó el límite").contains("3.33 %").contains("" + W1);
        assertThat(texts.get(1)).contains("CRITICAL").contains("jornada abierta del operario #" + W2);
        assertThat(texts.get(2)).contains("HIGH").contains("" + W4);
        assertThat(texts.get(3)).contains("2 jornadas terminaron entre el 90.00 % y el 100 %");
        assertThat(texts.get(4)).contains("operario #" + W1 + " (5)").doesNotContain("" + W2);
        assertThat(texts.get(5)).contains("6000").contains("2026-W45").contains("7800").contains("2026-W47")
                .contains("al alza");
        assertThat(texts.get(6)).contains(POSITION_NAME).contains("9800").contains("12360");

        List<Recommendation> recs = i.recommendations();
        assertThat(recs).extracting(Recommendation::priority).containsExactly(
                InsightPriority.ALTA, InsightPriority.ALTA, InsightPriority.ALTA,
                InsightPriority.MEDIA, InsightPriority.MEDIA, InsightPriority.MEDIA, InsightPriority.BAJA);
        assertThat(recs.get(0).action()).contains("Reprogramar").contains("" + W2);
        assertThat(recs.get(0).reason()).contains("CRITICAL").contains("182.43 %").contains("11:47 (America/Lima)");
        assertThat(recs.get(0).workerIds()).containsExactly(W2);
        assertThat(recs.get(1).action()).contains("Rotar").contains("" + W4).contains("" + W3);
        assertThat(recs.get(1).reason()).contains("120.00 %").doesNotContain("excedencia");
        assertThat(recs.get(1).workerIds()).containsExactly(W4, W3);
        assertThat(recs.get(2).action()).startsWith("Revisar con el operario #" + W1).contains("blindaje");
        assertThat(recs.get(2).workerIds()).containsExactly(W1);
        assertThat(recs.get(3).action()).contains("blindaje").contains(POSITION_NAME);
        assertThat(recs.get(3).positions()).containsExactly(new PositionRef(P1, POSITION_NAME));
        assertThat(recs.get(4).action()).isEqualTo("Programar rotación periódica y pausas para el operario #" + W1 + ".");
        assertThat(recs.get(4).reason()).startsWith("Termina con frecuencia");
        assertThat(recs.get(4).workerIds()).containsExactly(W1);
        assertThat(recs.get(5).action()).contains("planificación");
        assertThat(recs.get(6).action()).contains("capacitación");
        assertThat(recs.get(6).reason()).contains("2 jornadas con cobertura incompleta");

        assertThat(i.dataQuality()).contains("2 de 30 jornadas tienen cobertura incompleta")
                .doesNotContain("Pocos datos");
    }

    @Test
    void textsNameTheWorkersAndAffectedListsKeepTheIds() {
        WorkerNames names = new WorkerNames(java.util.Map.of(W1, "Carlos Rojas", W2, "Lucía Mendoza", W4, "Jorge Salas"));

        PeriodInsights i = policy.analyze(rich(), names, NOW);

        List<String> texts = i.findings().stream().map(Finding::text).toList();
        assertThat(texts.get(0)).endsWith("(3.33 %): Carlos Rojas.");
        assertThat(texts.get(1)).contains("jornada abierta de Lucía Mendoza");
        assertThat(texts.get(4)).contains("Carlos Rojas (5)");
        assertThat(i.recommendations().get(0).action()).startsWith("Reprogramar las tareas restantes de Lucía Mendoza");
        assertThat(i.recommendations().get(1).action())
                .isEqualTo("Rotar a Jorge Salas con el operario #" + W3 + " en lo que resta del turno.");
        assertThat(i.recommendations().get(1).workerIds()).containsExactly(W4, W3);
        assertThat(i.recommendations().get(2).action()).startsWith("Revisar con Carlos Rojas las tareas");
        assertThat(i.recommendations().get(4).action()).isEqualTo("Programar rotación periódica y pausas para Carlos Rojas.");
        assertThat(i.summary() + texts + i.recommendations()).doesNotContain("operario " + W1)
                .doesNotContain("operario #" + W1);
    }

    @Test
    void veryLongNamesAreTrimmedToTheLimits() {
        WorkerNames names = new WorkerNames(java.util.Map.of(W1, "X".repeat(500)));

        PeriodInsights i = policy.analyze(rich(), names, NOW);

        assertThat(i.findings()).allSatisfy(f -> assertThat(f.text()).hasSizeLessThanOrEqualTo(PeriodInsights.MAX_FINDING_LENGTH));
        assertThat(i.recommendations()).allSatisfy(r ->
                assertThat(r.action()).hasSizeLessThanOrEqualTo(PeriodInsights.MAX_ACTION_LENGTH));
    }

    @Test
    void emptyPeriodStillProducesEveryPart() {
        PeriodInsights i = policy.analyze(empty(), NOW);

        assertThat(i.summary()).contains("no hay jornadas cerradas")
                .contains("no hay operarios con proyección HIGH o CRITICAL");
        assertThat(i.findings()).singleElement().extracting(Finding::level).isEqualTo(FindingLevel.INFO);
        assertThat(i.recommendations()).singleElement().satisfies(r -> {
            assertThat(r.priority()).isEqualTo(InsightPriority.BAJA);
            assertThat(r.action()).contains("monitoreo");
        });
        assertThat(i.dataQuality()).contains("Sin jornadas cerradas");
    }

    @Test
    void fewSessionsAreFlaggedInDataQuality() {
        var s = rich();
        var few = new com.emsafe.aiforecasting.domain.model.AnalyticsSummary(s.organizationId(), s.from(), s.to(),
                s.zone(), s.generatedAt(),
                new com.emsafe.aiforecasting.domain.model.AnalyticsSummary.Totals(4, 2, 0, 0,
                        java.math.BigDecimal.ZERO, 0, java.math.BigDecimal.ZERO, 0, new java.math.BigDecimal("0.90")),
                List.of(), List.of(), List.of(), List.of(), List.of(), s.recommendations());

        assertThat(policy.analyze(few, NOW).dataQuality()).contains("Pocos datos: solo 4 jornadas cerradas");
    }

    @Test
    void stableAndFallingTrendsAreInfo() {
        var s = rich();
        var falling = new com.emsafe.aiforecasting.domain.model.AnalyticsSummary(s.organizationId(), s.from(),
                s.to(), s.zone(), s.generatedAt(), s.totals(), s.perWorker(),
                List.of(s.weeklyTrend().get(2), s.weeklyTrend().get(0)), s.topPositions(), s.workersAboveWarning(),
                s.projectedCriticalWorkers(), s.recommendations());

        PeriodInsights i = policy.analyze(falling, NOW);

        assertThat(i.findings()).filteredOn(f -> f.text().contains("tendencia"))
                .singleElement().satisfies(f -> {
                    assertThat(f.level()).isEqualTo(FindingLevel.INFO);
                    assertThat(f.text()).contains("a la baja");
                });
        assertThat(i.recommendations()).noneMatch(r -> r.action().contains("planificación"));
    }
}
