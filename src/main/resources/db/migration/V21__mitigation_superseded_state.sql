-- Estado SUPERSEDED para las medidas desplazadas por una propuesta más nueva.
--
-- La regla 5 del informe §5.6.1 exige que una sugerencia nueva reemplace a la
-- propuesta pendiente del mismo operario "dejando constancia", pero los seis
-- estados que define no incluyen ninguno para eso. Hasta ahora se cerraban como
-- NOT_VIABLE, y eso tiene una consecuencia que no es cosmética:
-- ComplianceReportFactory incluye en el reporte las acciones del período, así
-- que una propuesta desplazada aparecía en un registro que debe conservarse
-- cinco años (DS 005-2012-TR art. 35) como constancia de que NO se encontró
-- rotación viable. Eso nunca ocurrió.
--
-- Nota sobre el CHECK: MySQL no permite modificar una restricción en sitio, hay
-- que soltarla y volver a crearla. El nombre se conserva para que el esquema
-- siga siendo reconocible.

ALTER TABLE mitigation_actions DROP CHECK ck_mitigation_actions_state;

ALTER TABLE mitigation_actions ADD CONSTRAINT ck_mitigation_actions_state
    CHECK (state IN ('PROPOSED', 'APPROVED', 'APPLIED', 'FAILED',
                     'REJECTED', 'NOT_VIABLE', 'SUPERSEDED'));

-- Corrige las filas ya escritas con el significado antiguo. La condición es
-- precisa: solo las que tienen superseded_by_id, que es lo que distingue a una
-- acción desplazada de una que de verdad no tenía candidato viable.
UPDATE mitigation_actions
   SET state = 'SUPERSEDED',
       outcome = CONCAT('Superseded by action #', superseded_by_id)
 WHERE state = 'NOT_VIABLE'
   AND superseded_by_id IS NOT NULL;

-- La bitácora es append-only y no se reescribe: una transición a NOT_VIABLE que
-- ocurrió de verdad queda registrada tal cual. Se añade la entrada que refleja
-- la corrección, para que el historial explique el cambio en vez de ocultarlo.
INSERT INTO mitigation_action_history
    (organization_id, mitigation_action_id, from_state, to_state, actor_id, occurred_at)
SELECT organization_id, id, 'NOT_VIABLE', 'SUPERSEDED', NULL, NOW(6)
  FROM mitigation_actions
 WHERE state = 'SUPERSEDED'
   AND superseded_by_id IS NOT NULL;
