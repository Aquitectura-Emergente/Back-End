package com.emsafe.occupationalhealth.domain.services;

import com.emsafe.occupationalhealth.domain.model.ReportPeriod;
import com.emsafe.occupationalhealth.domain.model.ZoneRiskLevel;
import com.emsafe.occupationalhealth.domain.model.ZoneStatistics;

import java.util.List;

/**
 * Consulta a Radiation Monitoring la clasificación vigente de una zona de
 * planta (informe §5.6.1).
 *
 * Capa anticorrupción: el dominio recibe ZoneRiskLevel y nunca el modelo del
 * otro contexto. Si Radiation Monitoring cambia su clasificación, solo cambia
 * el adaptador.
 */
public interface ZoneExposurePort {

    /**
     * Nivel de riesgo vigente de la zona.
     *
     * Una zona sin datos recientes se informa como {@link ZoneRiskLevel#RISK}:
     * ante la duda no se manda a nadie. Es lo contrario al criterio de
     * DoseSnapshot —donde la falta de dato no bloquea al SSO— porque aquí el
     * dato ausente sí implica mover una persona a ciegas.
     */
    ZoneRiskLevel riskLevel(Long organizationId, Long plantZoneId);

    /**
     * Promedio, máximo y número de lecturas de cada zona en el período.
     *
     * Alimenta el reporte consolidado por zona, así que no devuelve nada que
     * identifique a una persona: solo agregados (RF32, RNF11).
     */
    List<ZoneStatistics> zoneStatistics(Long organizationId, ReportPeriod period);
}
