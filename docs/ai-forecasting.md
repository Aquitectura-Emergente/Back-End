# AI Forecasting & Analytics

Bounded context `com.emsafe.aiforecasting`. Proyecta el índice de exposición magnética acumulado
de cada operario hasta el fin de su turno, prioriza a los operarios por urgencia y recomienda una
rotación de puestos o la reprogramación de la tarea. Redacta además una explicación en lenguaje
natural, con Gemini o con una plantilla.

Este contexto **no decide** si un operario excedió su límite: esa decisión es de Personal Dosimetry.
Tampoco ejecuta recomendaciones: las publica para Occupational Health, donde decide el SSO.
Toda evaluación es una proyección (`isProjection = true`). El resumen analítico combina el
histórico medido por Personal Dosimetry (`isProjection = false`) con las proyecciones vigentes.

La exposición es un índice acumulado en **uT·s** (`MAGNETIC_TIME_INDEX_V1`), no una dosis.

## Contenido

- [Modelo](#modelo)
- [Reglas de cálculo](#reglas-de-cálculo)
- [API](#api)
- [Analítica](#analítica)
- [Análisis con IA](#análisis-con-ia)
- [Eventos salientes](#eventos-salientes)
- [Propiedades](#propiedades)
- [Persistencia](#persistencia)
- [Prueba manual en Swagger](#prueba-manual-en-swagger)
- [Pruebas automatizadas](#pruebas-automatizadas)
- [Semilla de demo](#semilla-de-demo)
- [Gemini y variables de entorno](#gemini-y-variables-de-entorno)
- [Integración con otros contextos](#integración-con-otros-contextos)
- [Pendientes conocidos](#pendientes-conocidos)

## Modelo

### Snapshot de exposición (modelo de lectura local)

`af_worker_exposure_snapshot` guarda una fila por sesión, con estos campos:
`organizationId`, `workerId`, `sessionId`, `zoneId`, `positionId`, `accumulatedIndex`,
`limitIndex`, `sessionStartedAt`, `plannedShiftEnd`, `capturedAt` y `updatedAt`. La fila no se
borra al cerrar la jornada, y su índice es el del último aviso, no el final: el índice final se
lee de Personal Dosimetry (ver [Analítica](#analítica)).

Existe porque ningún contexto entrega todos los datos que necesita la proyección. Se alimenta en
proceso (ver [Integración](#integración-con-otros-contextos)) y, para pruebas, por el inbox REST
(`exposure-snapshot`, `exposure-warning-raised` y `shift-updated`), con estas reglas:

- **Gana la captura más reciente.** Un snapshot o aviso con `capturedAt` anterior al guardado no
  sobrescribe el índice.
- **Los campos omitidos se conservan.** Si `zoneId`, `positionId` o `plannedShiftEnd` vienen
  vacíos, se mantienen los valores ya conocidos.
- **Un `sessionId` pertenece a un solo operario y organización.** Si un evento posterior trae otro
  operario u otra organización para esa sesión, la respuesta es `409`.
- **Fin de turno por defecto.** Si no se conoce `plannedShiftEnd`, el fin de turno es
  `sessionStartedAt + default-shift-hours`.

Los IDs de organización, operario, sesión, zona y puesto son identificadores de integración: no
hay claves foráneas hacia tablas de otros contextos.

### Agregado `PreventiveAssessment`

```
PROJECTED ──► ASSESSED ──► RECOMMENDED ──► CLOSED
                  └──────────────────────────► CLOSED
```

- **Transiciones inválidas.** Una transición fuera del diagrama lanza `ForecastingRuleException`,
  que responde `422`.
- **Evaluación cerrada.** Una evaluación `CLOSED` es inmutable; cualquier operación sobre ella
  lanza excepción.
- **Una evaluación activa por sesión.** Si llega un nuevo disparo para la misma sesión, la
  evaluación activa anterior se cierra con `closeReason = SUPERSEDED` y se crea una nueva. La base
  de datos lo garantiza con la columna `active_session_key` y una restricción `UNIQUE`.
- **Recomendaciones.** Solo las reciben las evaluaciones HIGH o CRITICAL, y siempre van dirigidas
  al operario evaluado.
- **Motivos de cierre** (`CloseReason`): `SUPERSEDED` (nuevo disparo de la misma sesión) o
  `SESSION_ENDED` (la jornada terminó en Personal Dosimetry; ver abajo).

### Evaluaciones vigentes y `SESSION_ENDED`

Una evaluación es activa por **sesión**, no por operario, y este contexto no recibe el cierre de
jornada. Antes, cada jornada con aviso dejaba su evaluación activa para siempre, y
`critical-workers` devolvía al mismo operario una vez por cada jornada vieja.

`OpenSessionAssessments` decide qué evaluaciones activas siguen **vigentes**, cruzando con Personal
Dosimetry por sus fachadas públicas. Una jornada terminó si:

- Personal Dosimetry la tiene `CLOSED` (`ExposureQueryFacade.closedSessions`), o
- el operario tiene **otra** jornada `OPEN` (`ExposureQueryFacade.currentExposure`): solo puede
  tener una abierta a la vez.

De las vigentes queda **una por operario**, la más reciente. Una sesión que Personal Dosimetry no
conoce (llegada solo por el inbox REST, como en la guía de Swagger) no se da por terminada.

| Dónde se usa | Efecto |
| --- | --- |
| `projections/critical-workers` | Solo evaluaciones vigentes, una por operario |
| `projections/workers/{workerId}` | La vigente del operario; 404 si no tiene |
| `analytics` (`projectedCriticalWorkers`) | El mismo criterio |
| `SessionEndReconciliationJob` (cada minuto) y la semilla de demo | Cierran las de jornadas terminadas con `closeReason = SESSION_ENDED` |

`assessments` y `assessments/{id}` no filtran: son el historial completo, incluidas las cerradas.

Cada evaluación guarda la versión del modelo que la produjo (`modelId`, `version` y el código
`LINEAR_RATE_V1`), el `limitVersionId` recibido de Personal Dosimetry y el `triggerEventId` del
evento que la disparó.

## Reglas de cálculo

Toda la aritmética usa `BigDecimal` con escala 6 y `RoundingMode.HALF_UP`. Las fechas se manejan en
UTC como `Instant`, con resolución de microsegundos para coincidir con `DATETIME(6)`.

### Proyección `LINEAR_RATE_V1`

Es determinista y reproducible. Con `current = accumulatedIndex` y `limit = limitIndex`:

| Magnitud | Fórmula |
| --- | --- |
| `elapsed` | `capturedAt − sessionStartedAt` en segundos; si es ≤ 0 → `422` |
| `rate` (`accumulationRate`) | `current / elapsed` |
| `remaining` | `max(0, shiftEnd − capturedAt)` |
| `projectedIndexAtShiftEnd` | `current + rate × remaining` |
| `remainingMargin` | `limit − current` (puede ser negativo) |
| `consumedRatio` | `current / limit` |
| `projectedRatio` | `projected / limit` |
| `estimatedExceedanceAt` | ver abajo |

Para calcular `estimatedExceedanceAt`:

- si `current ≥ limit`, es `capturedAt`;
- si `projected > limit` (estrictamente mayor, igual que Personal Dosimetry) y `rate > 0`, es
  `capturedAt + (limit − current) / rate` segundos, redondeado al milisegundo;
- en cualquier otro caso es `null`.

Un `limitIndex ≤ 0` responde `422`. Solo se acepta `methodId = MAGNETIC_TIME_INDEX_V1` con
`unit = uT*s`; cualquier otro par responde `422`.

### Candidatos de rotación

- **Pool de candidatos:** snapshots de la misma organización y zona, excluyendo al operario fuente
  y a quienes ocupan su mismo puesto. Un operario fuente sin zona o sin puesto no tiene candidatos.
- **Intercambio:** el operario fuente pasa al puesto del candidato y el candidato al del fuente.
  La tasa de cada operario representa la exposición de su puesto.
- **Candidato viable:** cumple dos condiciones a la vez:
  - `candidate.current + source.rate × remaining ≤ candidate.limit × safe-candidate-ratio`;
  - `candidate.rate < source.rate`.
- **Beneficio:** `(source.rate − candidate.rate) × remaining`. Gana el candidato viable de mayor
  beneficio. En caso de empate, gana el de menor `currentIndex` y luego el de menor `workerId`.
- **Datos inválidos:** un candidato con `elapsed ≤ 0` se descarta sin error, para no bloquear la
  evaluación del operario fuente.

### Criticidad

Se evalúa en este orden y gana la primera regla que se cumple. El margen crítico es
`critical-margin-ratio × limit`.

| Nivel | Condición |
| --- | --- |
| `CRITICAL` | `remainingMargin < margen crítico` y no hay candidato viable |
| `HIGH` | `projectedRatio > 1`, o `remainingMargin < margen crítico` con candidato viable |
| `MODERATE` | `projectedRatio ≥ moderate-projected-ratio` |
| `LOW` | cualquier otro caso |

### Recomendación

| Criticidad | Candidato viable | Recomendación | `estimatedBenefitIndex` |
| --- | --- | --- | --- |
| LOW / MODERATE | — | ninguna | — |
| HIGH / CRITICAL | sí | `ROTATION` hacia el candidato | beneficio del intercambio |
| HIGH / CRITICAL | no | `RESCHEDULE_TASK`, sin destino | `projected − current` (lo que falta acumular) |

El motivo (`reason`) de la recomendación contiene solo IDs.

### Redacción

- **Con LLM.** Con `llm.enabled=true`, `GeminiRedactionAdapter` llama a Gemini, reutilizando
  `gemini.api-key` y `gemini.model` (por defecto `gemini-3.8-flash`), con un `RestTemplate` propio y
  timeout `llm.timeout-ms` (por defecto 30000 ms). El razonamiento interno va al mínimo; ver
  [Gemini](#gemini-y-variables-de-entorno).
  - El prompt va en español y solo incluye IDs, porcentajes, horas y el tipo de recomendación,
    nunca nombres ni datos personales.
  - La key viaja en la cabecera `x-goog-api-key`, no en la URL.
- **Fallback.** `FallbackMessagePolicy` genera un texto con plantilla fija y las cifras. Se usa con
  el LLM deshabilitado (valor por defecto), ante error o timeout, o si la respuesta viene vacía.
- **Sin key.** Con `llm.enabled=true` y `gemini.api-key` vacía, la aplicación arranca, avisa **una
  vez** en el log (`llm.enabled=true but gemini.api-key is empty`) y usa directamente la
  plantilla (`FallbackOnlyRedactionAdapter`). Ver [Gemini](#gemini-y-variables-de-entorno).
- **La evaluación nunca falla por el LLM.** El campo `redaction.generatedBy` indica el origen:
  `LLM` o `FALLBACK`.
- **Historial de la semilla de demo.** Las evaluaciones que deja la semilla para jornadas pasadas se
  redactan siempre con la plantilla, sin llamar al LLM (`TemplateOnlyRedaction`). Ver
  [Semilla de demo](#semilla-de-demo).

## API

Prefijo: `/api/v1/ai-forecasting`. Todas las respuestas usan `ApiResponse` (`success`, `message`,
`data` y `timestamp`), y los listados usan `PageResponse` con `page` desde 0 y `size` entre 1 y 200.

| Método | Ruta | Acceso | Propósito | Errores |
| --- | --- | --- | --- | --- |
| POST | `/inbox/exposure-snapshot` | ADMIN | Alta o actualización del snapshot | 400, 403, 409, 422 |
| POST | `/inbox/exposure-warning-raised` | ADMIN | Dispara una evaluación | 400, 403, 409, 422 |
| POST | `/inbox/shift-updated` | ADMIN | Cambia zona, puesto o fin de turno y recalcula la evaluación activa | 400, 403, 409, 422 |
| GET | `/assessments/{id}` | ADMIN u organización dueña | Detalle de una evaluación | 403, 404 |
| GET | `/assessments?organizationId&workerId&sessionId&from&to&page&size` | ADMIN u organización dueña | Búsqueda paginada, más recientes primero | 400, 403 |
| GET | `/projections/workers/{workerId}` | ADMIN u organización dueña | Evaluación activa del operario | 403, 404 |
| GET | `/projections/critical-workers?organizationId&minCriticality&page&size` | ADMIN u organización dueña | Evaluaciones activas por criticidad descendente y luego por `estimatedExceedanceAt` ascendente (sin excedencia al final) | 400, 403 |
| GET | `/analytics/organizations/{organizationId}/summary?from&to&zone` | ADMIN u organización dueña | Resumen del histórico de jornadas y críticos proyectados (ver [Analítica](#analítica)) | 400, 403 |
| POST | `/analytics/organizations/{organizationId}/insights?from&to&zone&refresh` | ADMIN u organización dueña | Análisis consultivo del periodo para el SSO (ver [Análisis con IA](#análisis-con-ia)) | 400, 403 |
| GET | `/outbox?page&size` | ADMIN | Eventos pendientes (`publishedAt` nulo), los más antiguos primero | 400, 403 |
| POST | `/outbox/{eventId}/ack` | ADMIN | Marca un evento como publicado (idempotente) | 403, 404 |

Detalles de los parámetros:

- **`from` y `to`:** fechas ISO-8601 en UTC, que filtran por `assessedAt`.
- **`minCriticality`:** opcional e inclusivo (`LOW`, `MODERATE`, `HIGH` o `CRITICAL`); sin valor
  no filtra.
- **`organizationId`:** en `critical-workers` es obligatorio para ADMIN. Una organización dueña
  que lo omite consulta la suya.
- **Eventos repetidos:** un evento del inbox con un `eventId` ya procesado responde `200` con
  `duplicate = true` y no tiene efectos.

### Acceso

"Consultas" son `assessments`, `projections` y `analytics`.

| Rol | Organización que representa | Inbox y outbox | Consultas |
| --- | --- | --- | --- |
| ADMIN | ninguna (ve todas) | sí | todas las organizaciones |
| CLIENT con `clientType = company` | el id de su propio usuario | no | solo su organización |
| SSO_RESPONSIBLE | `users.organization_id` (docs/iam.md §3) | no | solo su organización |
| SSO_RESPONSIBLE con `organization_id` nulo | ninguna | no | no (403, nunca 500) |
| WORKER, TECHNICIAN, CLIENT individual | ninguna | no | no (403) |

- **Organización por parámetro.** Si el endpoint recibe `organizationId` (`assessments`,
  `critical-workers`) o lo lleva en la ruta (`analytics`), quien no es ADMIN solo puede usar el
  suyo; otro valor da 403. Si lo omite donde es opcional, se usa el suyo, nunca "todas".
- **Detalle por id.** `assessments/{id}` y `projections/workers/{workerId}` dan 403 si la
  evaluación es de otra organización. Un id inexistente da 404 antes de mirar la organización
  (ver [Pendientes](#pendientes-conocidos)).
- **WORKER.** Aunque tiene organización en IAM, no representa a ninguna aquí: las evaluaciones son
  de la organización, no del operario.

`IamForecastingActorDirectory` traduce la cuenta a la organización que representa y
`ForecastingAccessPolicy` decide solo con esa organización. La política se aplica en tres capas:

1. `SecurityConfig` exige un JWT válido en `/api/v1/ai-forecasting/**`.
2. Un `HandlerInterceptor` exige ADMIN en inbox y outbox **antes** de leer el cuerpo. Así, un
   no-ADMIN recibe `403` aunque el cuerpo sea inválido. `@PreAuthorize` no basta para esto, porque
   la lectura del JSON y `@Valid` ocurren antes de invocar el método protegido.
3. `@PreAuthorize("hasRole('ADMIN')")` en esos dos controllers, como segunda capa.

### Códigos de error

| Código | Cuándo |
| --- | --- |
| 400 | JSON mal formado; campo obligatorio ausente; más de 6 decimales en un índice; fecha o enum inválidos; `page` o `size` fuera de rango; `critical-workers` sin `organizationId` para ADMIN; en `analytics`, zona inválida, `from` posterior a `to` o periodo de más de 366 días |
| 403 | Sin token, token inválido o fuera de ámbito (el proyecto responde 403 en todos estos casos) |
| 404 | La evaluación, la evaluación activa del operario o el evento del outbox no existen |
| 409 | Aviso o cambio de turno de una sesión sin snapshot; sesión de otro operario u organización; escrituras concurrentes sobre la misma sesión o evento |
| 422 | Regla de dominio inválida: `elapsed ≤ 0`, `limitIndex ≤ 0`, índice negativo, método o unidad no soportados |

Los DTOs solo validan formato y presencia (`400`); las reglas de dominio responden `422`.
`AiForecastingExceptionHandler`, acotado al paquete, traduce `422`, `409` y los errores de formato;
`404` y `403` los resuelve `GlobalExceptionHandler`.

## Analítica

`GET /api/v1/ai-forecasting/analytics/organizations/{organizationId}/summary?from=&to=&zone=`,
de solo lectura y con el mismo [acceso](#acceso) que las consultas.

### Parámetros

| Parámetro | Por defecto | Regla |
| --- | --- | --- |
| `from` | `to` − 27 días | Fecha ISO (`2026-11-02`), primer día **inclusivo** en `zone` |
| `to` | hoy en `zone` | Último día **inclusivo** en `zone` |
| `zone` | `America/Lima` | Zona IANA; interpreta `from`/`to` y agrupa por semana. Inválida → 400 |

`from` posterior a `to`, o más de 366 días, responde 400. El acceso se comprueba antes de validar
el periodo y la zona (un formato de fecha mal escrito sí da 400 antes, al convertir el parámetro).

### De dónde sale cada dato

| Sección | Fuente | Nota |
| --- | --- | --- |
| `totals`, `perWorker`, `weeklyTrend`, `topPositions`, `workersAboveWarning` | `ExposureQueryFacade.closedSessions` (Personal Dosimetry): jornadas `CLOSED` con `endedAt` en el periodo | Dato medido. Las banderas de advertencia y excedencia son las de Personal Dosimetry |
| `topPositions[].positionName` | `JobPositionService.listFor` (Plant Configuration) | Si falla o no lo conoce, el nombre queda nulo y la respuesta no falla |
| `projectedCriticalWorkers` | Evaluaciones vigentes HIGH/CRITICAL de este contexto | Foto de **ahora**, no depende del periodo. Mismo criterio que `critical-workers`: jornadas no terminadas, una por operario |
| `recommendations` | Evaluaciones con `assessedAt` en el periodo | Por jornada cuenta solo la evaluación más reciente |

Reglas:

- **Cerca del límite:** índice final ≥ `near-limit-ratio` × límite (0.90 por defecto; incluye las
  excedidas).
- **Semana:** semana ISO (`2026-W47`) de `endedAt` en `zone`. Se listan todas las semanas del
  periodo, también las vacías.
- **Puestos:** hasta 10, por índice final promedio descendente.
- **Índices** con escala 6 (uT·s); **porcentajes** con 2 decimales.
- Las jornadas `CLOSING` no entran: todavía pueden cambiar. No requiere migración.

### Campos nulos

El `ObjectMapper` del proyecto es `non_null`: **un campo nulo no aparece en el JSON**. El cliente
debe tratar la ausencia como `null`. Pasa con `positionName` desconocido, con
`sessionsNearLimitPct` y `sessionsExceededPct` sin jornadas, con `avgFinalIndex` y
`maxFinalIndex` de una semana vacía, con `estimatedExceedanceAt` sin excedencia proyectada y con
`targetWorkerId` en una reprogramación.

### Ejemplo

Organización Quantum (`8`) con la [semilla de demo](#semilla-de-demo); listas recortadas y cifras
ilustrativas:

```json
{
  "success": true,
  "data": {
    "organizationId": 8,
    "from": "2026-11-04",
    "to": "2026-11-25",
    "zone": "America/Lima",
    "unit": "uT*s",
    "methodId": "MAGNETIC_TIME_INDEX_V1",
    "isProjection": false,
    "generatedAt": "2026-11-25T15:00:00.000000Z",
    "totals": {
      "closedSessions": 60, "workers": 4, "sessionsAboveWarning": 9,
      "sessionsNearLimit": 2, "sessionsNearLimitPct": 3.33,
      "sessionsExceeded": 1, "sessionsExceededPct": 1.67,
      "incompleteCoverageSessions": 1, "nearLimitRatio": 0.90
    },
    "perWorker": [
      { "workerId": 14, "sessions": 15, "avgFinalIndex": 9728.000000, "maxFinalIndex": 12360.000000,
        "maxRatio": 1.030000, "sessionsAboveWarning": 7, "sessionsNearLimit": 1, "sessionsExceeded": 1 }
    ],
    "weeklyTrend": [
      { "isoWeek": "2026-W48", "weekStart": "2026-11-23", "sessions": 8,
        "avgFinalIndex": 6600.000000, "maxFinalIndex": 10560.000000, "sessionsNearLimit": 0, "sessionsExceeded": 0 }
    ],
    "topPositions": [
      { "positionId": 31, "positionName": "Prensa hidráulica", "sessions": 15,
        "avgFinalIndex": 9728.000000, "maxFinalIndex": 12360.000000 }
    ],
    "workersAboveWarning": [
      { "workerId": 14, "sessionsAboveWarning": 7, "lastAt": "2026-11-24T21:00:00Z" }
    ],
    "projectedCriticalWorkers": [
      { "assessmentId": "…", "workerId": 41, "sessionId": "…", "criticality": "CRITICAL",
        "currentIndex": 10900.000000, "projectedIndex": 21891.000000, "limitIndex": 12000.000000,
        "projectedRatio": 1.824250, "estimatedExceedanceAt": "2026-11-25T16:47:00Z",
        "recommendationType": "RESCHEDULE_TASK", "isProjection": true }
    ],
    "recommendations": { "rotation": 0, "rescheduleTask": 6, "estimatedBenefitIndex": 61234.000000 }
  }
}
```

### Para qué sirve cada parte

| Parte | Reporte | Pantalla del SSO |
| --- | --- | --- |
| `totals` | Resumen ejecutivo | Tarjetas de indicadores |
| `weeklyTrend` | Tabla y gráfico | Gráfico de línea |
| `perWorker`, `topPositions` | Tablas | Tabla ordenable y barras |
| `workersAboveWarning` | Sí | Lista con enlace al operario |
| `projectedCriticalWorkers` | Opcional (foto del momento) | Principal |
| `recommendations`, `incompleteCoverageSessions` | Efecto de la IA y calidad del dato | Secundario |

## Análisis con IA

`POST /api/v1/ai-forecasting/analytics/organizations/{organizationId}/insights?from=&to=&zone=&refresh=`

Lectura del [resumen analítico](#analítica) del periodo, escrita para el responsable de SSO:

- un **resumen** de 2 o 3 frases;
- **hallazgos**, cada uno con su nivel (`INFO`, `ATENCION` o `CRITICO`);
- **recomendaciones** preventivas concretas (rotación, pausas, revisar el puesto o el blindaje,
  reprogramar tareas, capacitación), cada una con su motivo, su prioridad (`ALTA`, `MEDIA` o `BAJA`)
  y los operarios (`workerId`) y puestos afectados;
- en los textos, cada operario aparece con su **nombre** de IAM, o como "operario #id" si no se
  encuentra;
- la **calidad de los datos**: cobertura incompleta o pocas jornadas.

Usa el mismo [acceso](#acceso) que `summary`: ADMIN, la cuenta CLIENT company y el SSO_RESPONSIBLE de
su organización. Los demás reciben 403, y el acceso se comprueba antes que el periodo. Los parámetros
`from`, `to` y `zone` se validan igual que en `summary` (400). Es `POST` porque puede llamar a un
servicio externo con coste; no crea nada.

### Qué hace

1. `PeriodInsightsQueryHandler` resuelve el periodo y consulta la caché.
2. Si no hay caché, obtiene el `AnalyticsSummary` de `AnalyticsSummaryQueryHandler`, sin recalcular
   nada. La llamada al LLM queda fuera de la transacción de lectura.
3. El puerto de dominio `PeriodInsightsPort` produce el análisis:
   - `GeminiInsightsAdapter`, con `llm.enabled=true` y la key definida;
   - `FallbackOnlyInsightsAdapter` en los demás casos.

   Es el mismo patrón que la [redacción](#redacción). Los dos adaptadores están en
   `infrastructure/llm` y reutilizan `gemini.api-key`, `gemini.model`,
   `emsafe.ai-forecasting.llm.enabled` y `llm.timeout-ms`.
4. Gemini recibe el periodo, los totales, las estadísticas por operario, semana y puesto, las
   proyecciones vigentes y los conteos de recomendaciones, y debe devolver un JSON estructurado
   (`responseMimeType = application/json` y `responseSchema`). El prompt le exige:
   - usar **solo** los datos entregados y no inventar cifras;
   - sugerir acciones preventivas concretas;
   - recordar que el análisis es consultivo.
5. El adaptador valida la respuesta: la estructura, los valores de `nivel` y `prioridad`, de 1 a 8
   hallazgos y de 1 a 8 recomendaciones, y las longitudes máximas (`PeriodInsights.MAX_*`).

**Consultivo y de solo lectura.** No cambia criticidades, evaluaciones, recomendaciones ni eventos.
Siempre responde `isAdvisory = true` y un `disclaimer` que depende del origen:

| `generatedBy` | `disclaimer` |
| --- | --- |
| `LLM` | Análisis generado por IA (Gemini). Las cifras provienen del motor de pronóstico y deben ser validadas por el SSO. |
| `FALLBACK` | Análisis generado con reglas automáticas (IA no disponible). Las cifras provienen del motor de pronóstico y deben ser validadas por el SSO. |

**Caché.** Se guarda en memoria durante 10 minutos, por organización, periodo resuelto (`from` y
`to` ya calculados) y zona, así que pulsar varias veces no gasta llamadas. `refresh=true` genera un
análisis nuevo y renueva la caché. También se guarda el fallback, para que con Gemini caído no se
espere el timeout en cada clic. La caché es por instancia y se pierde al reiniciar.

### Privacidad

Ningún nombre, email ni ID real sale del backend. Los nombres se usan solo para los textos que lee
el SSO:

- Los operarios se envían como "Operario A", "Operario B"… y los puestos como "Puesto A"…
  (`InsightsPseudonyms`). Los seudónimos se asignan en cada llamada y no son estables entre llamadas.
- No se envían los IDs de organización, sesión ni evaluación, ni los nombres de los puestos.
  Este contexto no conoce nombres ni emails de las personas.
- Los nombres de los operarios se resuelven en el backend: `PeriodInsightsQueryHandler` los pide al
  puerto `WorkerNamePort`, cuyo adaptador es `IamWorkerNameAdapter` (IAM, solo lectura, igual que
  `IamForecastingActorDirectory`). El adaptador solo devuelve cuentas de la misma organización con
  nombre. Sin nombre, o si IAM falla, el texto dice "operario #id".
- Al recibir la respuesta (y también en el fallback), los seudónimos de los textos se reemplazan:
  - "al Operario A" pasa a "a Lucía Mendoza", o a "al operario #14" si no hay nombre;
  - "Puesto A" pasa a "Puesto Prensa hidráulica", o al ID si el puesto no tiene nombre.

  Si un nombre largo hace que un texto supere su límite, el texto se recorta con "…".
- En `operariosAfectados` los seudónimos se reemplazan por el `workerId`, y en `puestosAfectados`
  por `{positionId, positionName}`.
- Si el modelo usa un seudónimo que no existe, la respuesta se considera inválida.
- La key viaja en la cabecera `x-goog-api-key` y nunca aparece en logs.
- `GeminiInsightsAdapterTest` captura el cuerpo enviado y comprueba que no contiene IDs, nombres
  de IAM ni emails, y que el texto final sí trae los nombres.

### Fallback

Si el LLM está apagado (el valor por defecto) o no hay key, o si Gemini supera el timeout, falla
o devuelve algo inválido, `InsightsFallbackPolicy` (dominio, determinista) genera **la misma
estructura** a partir del resumen, con `generatedBy = FALLBACK`, sin `model` y con el disclaimer de
reglas automáticas. También nombra a los operarios. El análisis nunca falla por el LLM.

| Parte | Reglas del fallback |
| --- | --- |
| Resumen | Jornadas y operarios del periodo, cuántas terminaron cerca del límite y cuántas lo superaron, y cuántos operarios tienen ahora una proyección HIGH o CRITICAL |
| `CRITICO` | Excedencias del periodo; proyecciones CRITICAL |
| `ATENCION` | Proyecciones HIGH; jornadas entre `nearLimitRatio` y el 100 % del límite; operarios con 3 o más jornadas sobre la advertencia; tendencia semanal que sube más del 10 % |
| `INFO` | Puesto con mayor índice promedio; tendencia estable o a la baja |
| `ALTA` | La acción del motor para cada proyección (rotar con el candidato, o reprogramar si no hay candidato viable); revisar las excedencias con el operario |
| `MEDIA` | Revisar el blindaje del puesto con mayor índice si hubo jornadas cerca del límite; rotación periódica y pausas para los operarios recurrentes; revisar la planificación si la tendencia sube |
| `BAJA` | Capacitación sobre el uso del dosímetro si hubo cobertura incompleta; si no hay nada que atender, mantener el monitoreo |
| Calidad de datos | Jornadas con cobertura incompleta; menos de 10 jornadas cerradas ("pocos datos"); sin jornadas |

### Ejemplo

Respuesta real en modo `FALLBACK` con la [semilla de demo](#semilla-de-demo) (Quantum,
`from` = hoy − 21 días). Se muestran los primeros 3 hallazgos y 3 de las 7 recomendaciones.
Con Gemini, la forma es la misma, con `generatedBy = LLM`, `model = gemini-3.8-flash` y el disclaimer
de IA.

```json
{
  "success": true,
  "data": {
    "organizationId": 8,
    "generatedBy": "FALLBACK",
    "generatedAt": "2026-11-25T23:46:49.288080Z",
    "period": { "from": "2026-11-04", "to": "2026-11-25", "zone": "America/Lima" },
    "isAdvisory": true,
    "disclaimer": "Análisis generado con reglas automáticas (IA no disponible). Las cifras provienen del motor de pronóstico y deben ser validadas por el SSO.",
    "resumen": "Entre el 2026-11-04 y el 2026-11-25 se cerraron 60 jornadas de 4 operarios: 2 (3.33 %) terminaron a 90.00 % del límite o más y 1 (1.67 %) lo superaron. Ahora hay 2 operarios con proyección HIGH o CRITICAL en jornadas abiertas (1 CRITICAL).",
    "hallazgos": [
      { "texto": "1 jornada superó el límite en el periodo (1.67 %): Carlos Rojas.", "nivel": "CRITICO" },
      { "texto": "Proyección CRITICAL en la jornada abierta de Lucía Mendoza: se estima superar el límite antes del fin del turno.", "nivel": "CRITICO" },
      { "texto": "Proyección HIGH en la jornada abierta de Carlos Rojas: el índice se acerca al límite.", "nivel": "ATENCION" }
    ],
    "recomendaciones": [
      {
        "accion": "Reprogramar las tareas restantes de Lucía Mendoza (no hay candidato viable para rotar).",
        "motivo": "Proyección CRITICAL: 182.43 % del límite al fin del turno; excedencia estimada a las 19:09 (America/Lima).",
        "prioridad": "ALTA", "operariosAfectados": [14], "puestosAfectados": []
      },
      {
        "accion": "Revisar el blindaje, la distancia a la fuente y los tiempos de permanencia en el puesto Prensa hidráulica.",
        "motivo": "Es el puesto con mayor índice final promedio (9728 uT·s) y hubo jornadas cerca del límite.",
        "prioridad": "MEDIA", "operariosAfectados": [],
        "puestosAfectados": [{ "positionId": 1, "positionName": "Prensa hidráulica" }]
      },
      {
        "accion": "Reforzar la capacitación sobre el uso continuo del dosímetro.",
        "motivo": "1 jornada con cobertura incompleta: el índice real pudo ser mayor.",
        "prioridad": "BAJA", "operariosAfectados": [], "puestosAfectados": []
      }
    ],
    "calidadDatos": "1 de 60 jornadas tiene cobertura incompleta; su índice puede estar subestimado."
  }
}
```

`model` no aparece en modo `FALLBACK` porque Jackson omite los nulos. Los textos nombran a Carlos
Rojas y Lucía Mendoza, pero `operariosAfectados` sigue con sus `workerId` (`12` y `14`).

## Eventos salientes

Se escriben en `af_outbox` en la misma transacción que el cambio de estado del agregado.
`ForecastingOutboxRelay` los despacha en proceso cada 30 s. También se pueden leer con
`GET /outbox` y confirmar con `POST /outbox/{eventId}/ack`.

| Evento | Qué hace el relay | Significado de `publishedAt` |
| --- | --- | --- |
| `RotationSuggested` | Lo publica en proceso como `occupationalhealth...inbound.RotationSuggested`, conservando `eventId` y `correlationId` | Entregado. Si la entrega falla, queda pendiente y se reintenta; el inbox de OH deduplica por `eventId` |
| `PredictiveRiskDetected` | **No lo publica**: es un hecho interno (`ForecastingOutboxRelay.INTERNAL_EVENT_TYPES`) | "Despachado sin consumidor". Se marca para que no encabece la cola para siempre; el evento sigue entero en `af_outbox` |
| Cualquier otro tipo | No lo marca y avisa en el log | Queda nulo (pendiente) |

### Sobre común

| Campo | Valor |
| --- | --- |
| `eventId` | UUID |
| `eventType` | `RotationSuggested` o `PredictiveRiskDetected` |
| `schemaVersion` | `1` |
| `organizationId` | organización del operario |
| `aggregateId` | `id` de la evaluación |
| `occurredAt` | instante UTC |
| `correlationId` | el `correlationId` del evento entrante, o su `eventId` si no lo trae |
| `causationId` | `eventId` del evento entrante |
| `publishedAt` | `null` hasta el `ack` |

### `RotationSuggested`

Se emite cuando la evaluación tiene recomendación (HIGH o CRITICAL). El `payload` es plano y
coincide con `RotationSuggestedInput` del inbox de Occupational Health
(`POST /api/v1/occupational-health/inbox/rotation-suggested`):

```json
{
  "organizationId": 8,
  "workerId": 101,
  "suggestionId": "<assessmentId>",
  "candidateWorkerIds": [200],
  "projectedExceedanceAt": "2026-11-10T14:40:00",
  "criticality": "HIGH",
  "rationale": "<texto de la redacción>"
}
```

- **`projectedExceedanceAt`** es un **LocalDateTime en UTC, sin zona**, porque Occupational Health
  lo recibe como `LocalDateTime`. Si no hay excedencia proyectada el valor es `null`, y con la
  configuración Jackson del proyecto (`non_null`) puede no aparecer en el JSON. Hay que tratar la
  ausencia como `null`.
- **`candidateWorkerIds`** queda vacío cuando la recomendación es `RESCHEDULE_TASK`.

### `PredictiveRiskDetected` (interno)

Se emite con criticidad HIGH o CRITICAL, siempre junto con un `RotationSuggested` de la misma
evaluación. Es un **evento interno, fuera de alcance como integración**: queda en `af_outbox` como
registro de dominio, pero ningún contexto lo consume. No se convierte en alerta a propósito:

- **Evitar el aviso duplicado.** Proviene del mismo `ExposureWarningRaised` de Personal Dosimetry
  (`causationId`), por el que Occupational Health ya crea la alerta `warning` (que también le
  llega al SSO por `/topic/organizations/{id}/alerts`). Una alerta predictiva sería la segunda
  por la misma jornada y el mismo instante.
- **Lo accionable ya llega.** Viaja en `RotationSuggested`, que abre la propuesta de mitigación.
- **El riesgo predictivo se consulta**, no se notifica: `critical-workers` y `analytics`.

Payload:

```json
{
  "organizationId": 8,
  "assessmentId": "<assessmentId>",
  "workerId": 101,
  "criticality": "HIGH",
  "estimatedExceedanceAt": "2026-11-10T14:40:00Z",
  "projectedIndex": 14400.000000,
  "limitIndex": 12000.000000,
  "isProjection": true
}
```

Aquí `estimatedExceedanceAt` es un `Instant` ISO-8601 con `Z`. Una evaluación LOW o MODERATE no
emite eventos.

## Propiedades

Todas están en `application.properties`. Los umbrales son ratios en `(0, 1]`; un valor fuera de
rango impide arrancar la aplicación.

| Propiedad | Por defecto | Uso |
| --- | --- | --- |
| `emsafe.ai-forecasting.default-shift-hours` | `8` | Turno cuando no llega `plannedShiftEnd` |
| `emsafe.ai-forecasting.critical-margin-ratio` | `0.10` | Margen crítico de CRITICAL y HIGH |
| `emsafe.ai-forecasting.moderate-projected-ratio` | `0.90` | Umbral de MODERATE |
| `emsafe.ai-forecasting.safe-candidate-ratio` | `0.90` | Techo del candidato de rotación |
| `emsafe.ai-forecasting.near-limit-ratio` | `0.90` | Analítica: jornada "cerca del límite" si índice final ≥ ratio × límite |
| `emsafe.ai-forecasting.model-id` | `LINEAR_RATE` | Se guarda en cada evaluación |
| `emsafe.ai-forecasting.model-version` | `1` | Código resultante: `LINEAR_RATE_V1` |
| `emsafe.ai-forecasting.llm.enabled` | `false` | `false` = solo plantilla, sin red (siempre en tests) |
| `emsafe.ai-forecasting.llm.timeout-ms` | `30000` | Timeout de conexión y de lectura con Gemini (variable `EMSAFE_AIFORECASTING_LLM_TIMEOUTMS`) |
| `gemini.model` | `gemini-3.8-flash` | Modelo de Gemini (variable `GEMINI_MODEL`). Compartido con el chat Astra de Client Portal |
| `emsafe.ai-forecasting.seed-demo` | `false` | [Semilla de demo](#semilla-de-demo) de Quantum. Nunca en producción |
| `emsafe.ai-forecasting.outbox-relay-enabled` | `true` | Relay de `af_outbox` (`false` en el perfil de test) |
| `emsafe.ai-forecasting.outbox-relay-delay-ms` | `30000` | Pausa entre pasadas del relay |
| `emsafe.ai-forecasting.session-end-reconcile-enabled` | `true` | Cierre con `SESSION_ENDED` de las evaluaciones de jornadas terminadas (`false` en el perfil de test) |
| `emsafe.ai-forecasting.session-end-reconcile-delay-ms` | `60000` | Pausa entre pasadas de ese cierre |

## Persistencia

La migración es `V18__ai_forecasting.sql` (MySQL) y todas sus tablas llevan el prefijo `af_`:

| Tabla | Contenido |
| --- | --- |
| `af_preventive_assessment` | Agregado. Índices `(organization_id, status)`, `(worker_id)`, `(session_id)` y `(assessed_at)`; `UNIQUE (active_session_key)` |
| `af_worker_exposure_snapshot` | Snapshot por sesión, con clave primaria `session_id`. Índices `(organization_id, zone_id)` y `(worker_id)` |
| `af_outbox` | Eventos salientes. `id` autoincremental para el orden; `UNIQUE (event_id)`; payload JSON en `TEXT` |
| `af_processed_message` | Idempotencia del inbox, con clave primaria `event_id` |

Los índices en uT·s se guardan como `DECIMAL(38,6)` y las fechas como `DATETIME(6)`. Los enums se
guardan como `VARCHAR`, no como `ENUM` de MySQL, para que agregar un valor no requiera migración.

La analítica, el acceso del SSO y la semilla de demo **no agregaron migraciones**: el histórico se
lee de Personal Dosimetry por su fachada. En `develop`, V18 ya se aplica en orden después de las
migraciones de los demás contextos (hoy la última es V29).

## Prueba manual en Swagger

Pasos previos:

1. Arranca la aplicación con el perfil `dev` y abre `http://localhost:8080/swagger-ui.html`.
2. Usa JDK 17: con JDK 23 o superior Lombok no se ejecuta y el proyecto no compila.
3. Ten a mano un fin de turno de referencia. Los ejemplos usan fechas fijas del 2026-11-10: turno de
   08:00 a 16:00 UTC, captura a las 12:00. La proyección no depende de la hora actual, así que
   puedes copiarlos tal cual.

### 1. Login

En `POST /api/auth/login`, inicia sesión como administrador:

```json
{ "email": "admin@emsafe.com", "password": "admin123" }
```

Copia `data.token`, pulsa **Authorize** y pega `Bearer <token>`.

Para usar una organización real como dueña, inicia sesión también con
`{"email":"ops@quantumdyn.com","password":"client123"}` y anota su `data.userId`. Ese valor es su
`organizationId`; en una base recién sembrada suele ser `8`. Los ejemplos usan `8`.

### 2. Snapshots de 3 operarios

En `POST /api/v1/ai-forecasting/inbox/exposure-snapshot` envía los 3 cuerpos. Todos están en la
zona `Z-1` y tienen un límite de 12000 uT·s.

Operario 101, puesto 10, que acumula rápido:

```json
{
  "eventId": "snap-101", "organizationId": 8, "workerId": 101, "sessionId": "S-101",
  "zoneId": "Z-1", "positionId": 10, "accumulatedIndex": 6000, "limitIndex": 12000,
  "sessionStartedAt": "2026-11-10T08:00:00Z", "plannedShiftEnd": "2026-11-10T16:00:00Z",
  "capturedAt": "2026-11-10T12:00:00Z"
}
```

Operario 200, puesto 20, que acumula lento y es el candidato viable:

```json
{
  "eventId": "snap-200", "organizationId": 8, "workerId": 200, "sessionId": "S-200",
  "zoneId": "Z-1", "positionId": 20, "accumulatedIndex": 1440, "limitIndex": 12000,
  "sessionStartedAt": "2026-11-10T08:00:00Z", "plannedShiftEnd": "2026-11-10T16:00:00Z",
  "capturedAt": "2026-11-10T12:00:00Z"
}
```

Operario 300, puesto 30, casi en el límite:

```json
{
  "eventId": "snap-300", "organizationId": 8, "workerId": 300, "sessionId": "S-300",
  "zoneId": "Z-1", "positionId": 30, "accumulatedIndex": 11000, "limitIndex": 12000,
  "sessionStartedAt": "2026-11-10T08:00:00Z", "plannedShiftEnd": "2026-11-10T16:00:00Z",
  "capturedAt": "2026-11-10T12:00:00Z"
}
```

Cada uno responde `200` con `"duplicate": false`. Si repites un cuerpo, responde
`"duplicate": true` sin cambios.

### 3. exposure-warning-raised

En `POST /api/v1/ai-forecasting/inbox/exposure-warning-raised` envía primero el aviso del operario
101:

```json
{
  "eventId": "warn-101", "correlationId": "demo-1", "organizationId": 8,
  "sessionId": "S-101", "workerId": 101, "accumulatedIndex": 7200, "limitIndex": 12000,
  "limitVersionId": 7, "capturedAt": "2026-11-10T12:00:00Z",
  "methodId": "MAGNETIC_TIME_INDEX_V1", "unit": "uT*s"
}
```

Anota `data.assessmentId`. El resultado esperado, calculado a mano:

- tasa `7200 / 14400 s = 0.5`; quedan 14400 s de turno;
- índice proyectado `7200 + 0.5 × 14400 = 14400`, es decir `projectedRatio = 1.2`;
- excedencia estimada `12:00 + (12000 − 7200) / 0.5 s = 14:40 UTC`;
- criticidad **HIGH**, porque se proyecta sobre el límite y el margen (4800) es mayor que 1200;
- el operario 200 es viable: `1440 + 0.5 × 14400 = 8640 ≤ 10800` y su tasa 0.1 es menor que 0.5;
- el operario 300 no es viable, porque su tasa (≈ 0.764) es mayor que 0.5;
- por tanto la recomendación es **ROTATION** con el operario 200, con beneficio
  `(0.5 − 0.1) × 14400 = 5760`.

Envía después el aviso del operario 300:

```json
{
  "eventId": "warn-300", "correlationId": "demo-1", "organizationId": 8,
  "sessionId": "S-300", "workerId": 300, "accumulatedIndex": 11500, "limitIndex": 12000,
  "limitVersionId": 7, "capturedAt": "2026-11-10T12:00:00Z",
  "methodId": "MAGNETIC_TIME_INDEX_V1", "unit": "uT*s"
}
```

El margen (500) es menor que 1200 y no hay candidato viable, así que la criticidad es
**CRITICAL** y la recomendación **RESCHEDULE_TASK**, con una excedencia estimada de ≈ 12:10 UTC.

Para comprobar los errores:

- cambiar `methodId` a `OTHER` responde `422`;
- enviar un aviso para `"sessionId": "S-999"`, que no tiene snapshot, responde `409`;
- enviar `"accumulatedIndex": 1.1234567` responde `400`.

### 4. Consultas

| Operación | Esperado |
| --- | --- |
| `GET /api/v1/ai-forecasting/assessments/{assessmentId}` | `status = RECOMMENDED`, `criticality = HIGH`, `projection.estimatedExceedanceAt = 2026-11-10T14:40:00Z`, `recommendation.type = ROTATION`, `targetWorkerId = 200`, `redaction.generatedBy = FALLBACK` |
| `GET /projections/workers/101` | La misma evaluación activa |
| `GET /projections/critical-workers?organizationId=8` | Orden: 300 (CRITICAL) y luego 101 (HIGH). El 200 no aparece porque no tiene evaluación |
| `GET /projections/critical-workers?organizationId=8&minCriticality=CRITICAL` | Solo el 300 |
| `GET /assessments?organizationId=8&workerId=101` | Una evaluación |

Para comprobar el control de acceso, autoriza con el token de `facilities@harbormed.com`, que es
otra organización, y repite `GET /assessments/{assessmentId}`: responde `403`.

### 5. Outbox

El relay despacha el outbox cada 30 s, así que los eventos dejan de estar pendientes enseguida. Para
verlos, arranca con `--emsafe.ai-forecasting.outbox-relay-enabled=false`. Con el relay apagado,
`GET /api/v1/ai-forecasting/outbox` lista 4 eventos pendientes, en este orden:

1. `RotationSuggested` del 101, con `candidateWorkerIds: [200]` y
   `projectedExceedanceAt: "2026-11-10T14:40:00"`;
2. `PredictiveRiskDetected` del 101;
3. `RotationSuggested` del 300, con `candidateWorkerIds: []`;
4. `PredictiveRiskDetected` del 300.

Todos llevan `causationId` con el `eventId` del aviso correspondiente y `correlationId = demo-1`.

### 6. Ack

En `POST /api/v1/ai-forecasting/outbox/{eventId}/ack`, con el `eventId` del primer evento:

- responde con `publishedAt` informado;
- repetirlo devuelve la misma fecha;
- `GET /outbox` ya no muestra el evento;
- un `eventId` inexistente responde `404`.

## Pruebas automatizadas

Con JDK 17 (con JDK 23 o superior Lombok no se ejecuta). En PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot'   # o la ruta de tu JDK 17
.\mvnw.cmd clean test
```

| Capa | Clases |
| --- | --- |
| Dominio (sin Spring) | `ExposureProjectionServiceTest`, `RotationCandidateSelectorTest`, `CriticalityAssessmentServiceTest`, `RotationRecommendationServiceTest`, `PreventiveAssessmentTest`, `FallbackMessagePolicyTest`, `WorkerExposureSnapshotTest`, `AssessmentPriorityTest`, `ValueObjectsTest`, `ExposureAnalyticsCalculatorTest`, `InsightsFallbackPolicyTest` (incluye nombres en los textos) y `DomainPurityTest` (falla si el dominio importa Spring, JPA o Lombok) |
| Aplicación, con fakes en memoria | `TriggerAssessmentHandlerTest`, `UpsertExposureSnapshotHandlerTest`, `ShiftUpdatedHandlerTest`, `QueryHandlersTest`, `OutboxHandlersTest`, `ForecastingAccessPolicyTest` (incluye SSO), `AnalyticsSummaryQueryHandlerTest`, `OpenSessionAssessmentsTest` (vigentes y cierre con `SESSION_ENDED`), `PeriodInsightsQueryHandlerTest` (caché, `refresh`, periodo inválido, solo lectura) |
| Infraestructura | `AiForecastingPersistenceTest` (`@DataJpaTest` sobre H2), `GeminiRedactionAdapterTest` (`MockRestServiceServer`: éxito, timeout, vacío, error), `GeminiClientTest` (`thinkingConfig` por modelo y reintento sin él), `NaturalLanguagePortWiringTest` (qué adaptador según `llm.enabled` y la key), `GeminiInsightsAdapterTest` (JSON válido con seudónimos reemplazados por nombres; JSON inválido, timeout y error caen al fallback; prompt sin IDs ni nombres), `PeriodInsightsPortWiringTest`, `IamWorkerNameAdapterTest`, `ForecastingOutboxRelayTest`, `IamForecastingActorDirectoryTest`, `AnalyticsAdaptersTest`, `ShiftUpdatedEventListenerTest`, `ForecastingPropertiesTest`, `DemoExposurePlanTest`, `AiForecastingDemoSeederTest` (semilla de punta a punta con el relay de Personal Dosimetry, alertas, escalamiento y el análisis con IA en modo fallback) |
| REST | `AiForecastingControllerTest` (contrato web y Swagger), `AiForecastingApiIntegrationTest` (JWT real por login: acceso por rol incluido SSO y WORKER, flujo, contrato de Occupational Health, ack, duplicados y 400/404/409/422) y `AiForecastingAnalyticsApiTest` (analítica y análisis con IA con JWT real: acceso, 400, fallback, caché y `refresh`) e `InsightsResourcesTest` (disclaimer por origen) |

Fuera del paquete, `ExposureQueryFacadeTest` y `ExposureSessionRepositoryEndedBetweenTest` (Personal
Dosimetry) cubren la fachada `closedSessions` que usa la analítica.

La correspondencia entre la migración V18 y las entidades se verificó arrancando con el perfil
`dev` contra MySQL 8 y `ddl-auto=validate`.

## Semilla de demo

Con `emsafe.ai-forecasting.seed-demo=true` (por defecto `false`; **nunca en producción**),
`AiForecastingDemoSeeder` ejecuta un guion determinista (`DemoExposurePlan`) sobre la empresa
Quantum (`ops@quantumdyn.com`) al arrancar, después de `DataInitializer`. **No escribe en tablas de
otros contextos**: todo pasa por sus servicios públicos, con las mismas reglas que la API.

| Contexto | Vía | Qué crea, si falta |
| --- | --- | --- |
| IAM | `UserRepository` (como `DataInitializer`), clave con el `PasswordEncoder` | `operario3/4/5@quantumdyn.com`, WORKER activos de Quantum, clave `worker123`. Usa también `operario1`. Si un email existe con otro rol u organización, se salta |
| Plant Configuration | `PlantZoneService`, `JobPositionService` | Zona "Planta AF demo" y puestos Prensa hidráulica, Soldadura e Inspección de calidad |
| Device Management | `DeviceCatalog`, `DeviceService.create` | Un `PERSONAL_DOSIMETER` por operario (`AF-DEMO-DOS-01..04`) |
| Personal Dosimetry | `PersonalDosimetryService` (actor ADMIN), `ExposureQueryFacade` | Membresías OPERATOR de los operarios y **SSO de `sso@quantumdyn.com`**, límite 12000 uT·s con advertencia 0.8, **60 jornadas cerradas** (15 días hábiles de las últimas 3 semanas × 4 operarios, 08:00–16:00 de Lima) y **una abierta hoy** por operario, iniciada hace 4 h |
| Alerts | `AlarmService.forWorker`, `acknowledgeAs`, `resolve` | Acusa y resuelve las alertas de las jornadas del historial |

**Sin LLM para el historial.** La semilla entrega los avisos de las jornadas pasadas drenando el relay
en el hilo de arranque, dentro de `TemplateOnlyRedaction`. Esas evaluaciones se redactan con la
plantilla aunque `llm.enabled=true`, así que el arranque no espera a Gemini ni gasta cuota. Las
evaluaciones de las jornadas abiertas hoy las entrega después el relay programado, fuera de ese
ámbito, y usan Gemini si está activo. Lo prueba
`AiForecastingDemoSeederTest.theSeedNeverAsksTheLlmToRedactHistoricalAssessments`.

**Membresía SSO.** Con ella, el SSO de Quantum (`sso@quantumdyn.com` / `sso12345`) puede usar desde
el portal la pantalla `/sso/exposure` (`GET /api/sso/workers`, `GET .../workers/{id}/sessions`,
`GET .../sessions/{id}/readings`) y abrir y cerrar jornadas de sus operarios (`POST .../sessions`,
`POST .../sessions/{id}/close`). Sin ella, Personal Dosimetry le responde 403 aunque su rol en IAM
sea SSO_RESPONSIBLE. Es idempotente: concederla otra vez con la misma organización y rol no cambia
nada; si la cuenta no es un SSO_RESPONSIBLE activo de Quantum, se avisa en el log y se salta. **En
producción no hay semilla: la membresía la concede un ADMIN** con
`POST /api/v1/personal-dosimetry/memberships` y el cuerpo
`{"userId": <id del SSO>, "organizationId": <id de la empresa>, "role": "SSO"}`.

El guion deja 9 jornadas sobre la advertencia, 2 cerca del límite, **una sola excedencia** y una
con cobertura incompleta. Hoy, operario1 y operario3 quedan sobre la advertencia: AI Forecasting
los evalúa como HIGH y CRITICAL cuando el relay entrega el aviso.

Los hechos llegan a los demás contextos por el relay de Personal Dosimetry, como con datos reales
(snapshots, evaluaciones, alertas, una propuesta de mitigación y los cierres). Para que el
historial no aparezca como actual:

1. **Drenado.** Tras sembrar el historial, el seeder drena ese relay (`drainOnce`, tope de 20
   pasadas). Si está apagado (`emsafe.personal-dosimetry.outbox-relay-enabled=false`), lo avisa en
   el log y no hace este paso.
2. **Alertas del historial.** Las 10 alertas (9 de advertencia y 1 danger) se acusan como su
   operario y se resuelven en el acto, antes de la ventana de 60 s del escalamiento automático:
   nada se escala al SSO.
3. **Evaluaciones del historial.** Las 9 que dejan sus avisos se cierran en el acto con
   `SESSION_ENDED`, sin esperar a la tarea programada.
4. **Jornadas de hoy.** Se abren después; sus 2 alertas de advertencia quedan **pendientes**,
   porque esas jornadas siguen abiertas. `critical-workers` devuelve exactamente esas 2 (operario3
   CRITICAL y operario1 HIGH).

**Idempotente.** Si los operarios demo ya tienen jornadas en la ventana, el historial no se
repite. Una jornada abierta de un arranque anterior que ya cumplió sus 8 h se cierra y se abre la
de hoy. Un paso que falla se registra y no detiene a los demás. En `prod`, `DataInitializer` no
corre: sin Quantum ni el ADMIN demo, la semilla solo registra "skipped".

Limitación: Personal Dosimetry no conoce la zona, así que sin un `ShiftUpdated` de Occupational
Health no hay candidatos de rotación y las recomendaciones de la demo son `RESCHEDULE_TASK`.

## Gemini y variables de entorno

`gemini.api-key` no tiene valor por defecto en ningún perfil (`${GEMINI_API_KEY:}`). La clave que
estuvo en `application-dev.properties` sigue en el historial de git y debe regenerarse. La misma
propiedad la usa el chat de Client Portal.

**Modelo.** `gemini.model=${GEMINI_MODEL:gemini-3.8-flash}` en los perfiles `dev`, `demo` y `prod`.
`gemini-2.5-flash` ya no está disponible para claves nuevas. El chat Astra de Client Portal lee la
misma propiedad, así que también pasa a usar `gemini-3.8-flash`, salvo que se defina `GEMINI_MODEL`.

**Timeout.** `llm.timeout-ms` vale 30000 ms por defecto (antes 5000). Con 5 s, los modelos con
razonamiento interno fallaban con `Read timed out`. El timeout aplica a la conexión y a la lectura.
Una evaluación en vivo puede esperar hasta ese tiempo antes de caer a la plantilla.

**Razonamiento al mínimo (`thinkingConfig`).** `GeminiClient` lo envía en `generationConfig` en
cada llamada, tanto de la redacción como del análisis:

| Modelo | Se envía primero | Si el modelo lo rechaza |
| --- | --- | --- |
| `gemini-2.*` | `{"thinkingBudget": 0}` | Sin `thinkingConfig` |
| Otros (p. ej. `gemini-3.8-flash`) | `{"thinkingLevel": "minimal"}` | `{"thinkingLevel": "low"}` y, si también lo rechaza, sin `thinkingConfig` |

Se considera rechazo un 400 cuyo cuerpo menciona "thinking". El reintento es inmediato y la opción
rechazada se recuerda para las llamadas siguientes. Un timeout, una key inválida u otro error no se
reintentan.

| `llm.enabled` | `GEMINI_API_KEY` | Resultado |
| --- | --- | --- |
| `false` (por defecto) | da igual | Plantilla (`FallbackOnlyRedactionAdapter`). Log: `LLM disabled, using template redaction` |
| `true` | vacía | Arranca, avisa **una vez** (`llm.enabled=true but gemini.api-key is empty`) y usa la plantilla |
| `true` | definida | `GeminiRedactionAdapter`. Ante error, timeout o respuesta vacía, plantilla y aviso por evaluación |

El [Análisis con IA](#análisis-con-ia) sigue la misma tabla: con el LLM apagado o sin key usa
`FallbackOnlyInsightsAdapter` (log: `period insights use deterministic rules`); con la key, usa
`GeminiInsightsAdapter` (log: `Gemini period insights enabled`).

Prueba local con Gemini, solo para esa terminal de PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot'
$s = Read-Host 'GEMINI_API_KEY' -AsSecureString      # no queda en el historial
$env:GEMINI_API_KEY = [System.Net.NetworkCredential]::new('', $s).Password
$env:EMSAFE_AIFORECASTING_LLM_ENABLED = 'true'
# opcionales: $env:GEMINI_MODEL = 'gemini-3.8-flash'; $env:EMSAFE_AIFORECASTING_LLM_TIMEOUTMS = '30000'
.\mvnw.cmd spring-boot:run
# al terminar
Remove-Item Env:GEMINI_API_KEY, Env:EMSAFE_AIFORECASTING_LLM_ENABLED
```

Debe aparecer `AI Forecasting: Gemini redaction enabled` y, tras un aviso, la evaluación debe traer
`redaction.generatedBy = LLM`.

### Variables del despliegue (Azure, perfil `prod`)

| Variable | ¿Obligatoria? | Propósito |
| --- | --- | --- |
| `GEMINI_API_KEY` | No | Clave de Gemini para la redacción (y el chat de Client Portal). Vacía: plantilla |
| `EMSAFE_AIFORECASTING_LLM_ENABLED` | No (`false`) | `true` para redactar con Gemini |
| `GEMINI_MODEL` | No (`gemini-3.8-flash`) | Modelo de Gemini para la redacción, el análisis con IA y el chat Astra |
| `EMSAFE_AIFORECASTING_LLM_TIMEOUTMS` | No (`30000`) | Timeout de conexión y de lectura con Gemini, en ms |
| `EMSAFE_AIFORECASTING_SEEDDEMO` | **No debe existir** (o `false`) | Semilla de demo |
| `EMSAFE_AIFORECASTING_OUTBOXRELAYENABLED` | No (`true`) | Relay que entrega `RotationSuggested` a Occupational Health |
| `JWT_SECRET` | **Sí** (plataforma) | Validación de tokens; sin ella `prod` no arranca |
| `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD` | **Sí** (plataforma) | Base de datos, incluidas las tablas `af_*` |
| `CORS_ALLOWED_ORIGINS` | En la práctica sí (plataforma) | Que el portal pueda llamar a la API |

Los umbrales tienen valores por defecto en `application.properties` y no hace falta definirlos.

## Integración con otros contextos

Todo en proceso (sin broker). Ningún adaptador lee tablas de otro contexto.

| Dirección | Pieza | Qué hace |
| --- | --- | --- |
| Personal Dosimetry → AI Forecasting | `DosimetryOutboxRelay` (PD) publica; `DosimetryInboxEventListener` (AF) consume | `ExposureSessionOpened` → `UpsertExposureSnapshotCommand` (snapshot con `zoneId` nulo); `ExposureWarningRaised` → `ExposureWarningRaisedCommand` (evaluación). Síncrono y `REQUIRES_NEW`: un fallo, por ejemplo un 409 porque el aviso llegó antes que la apertura, deja el evento pendiente en PD y se reintenta. Idempotencia por `af_processed_message` |
| Personal Dosimetry → AI Forecasting | `ExposureQueryFacade.closedSessions` y `currentExposure` | Histórico de jornadas cerradas para la analítica, y qué jornadas terminaron para las [evaluaciones vigentes](#evaluaciones-vigentes-y-session_ended) (solo lectura) |
| Occupational Health → AI Forecasting | `ShiftUpdatedEventListener` | `ShiftUpdated` → `ShiftUpdatedHandler` (zona, puesto y recálculo de la evaluación activa). `AFTER_COMMIT` y `REQUIRES_NEW`; un 409 (sesión sin snapshot) se registra y no se propaga |
| AI Forecasting → Occupational Health | `ForecastingOutboxRelay` | `RotationSuggested` → inbox de OH (propuesta de mitigación), conservando `eventId` y `correlationId`. `PredictiveRiskDetected` es interno (ver [Eventos salientes](#eventos-salientes)) |
| Plant Configuration → AI Forecasting | `JobPositionService.listFor` | Nombres de puesto en la analítica |
| IAM → AI Forecasting | `IamForecastingActorDirectory` (`UserRepository`, solo lectura) | Rol y organización de la cuenta ([Acceso](#acceso)) |

`SecurityConfig` exige autenticación en `/api/v1/ai-forecasting/**`, declarada antes del
`permitAll` de `/api/v1/**`. Ese `permitAll` sigue existiendo para el resto de `/api/v1` (docs/iam.md
§7): si se quita, esta ruta no cambia; si una regla nueva se declara después de él, queda abierta.
`AiForecastingApiIntegrationTest` falla si `/api/v1/ai-forecasting/**` queda sin autenticar.

## Pendientes conocidos

- **Cierre de jornada por consulta, no por evento.** Este contexto no recibe el cierre de jornada,
  así que lo averigua en Personal Dosimetry (ver [Evaluaciones vigentes](#evaluaciones-vigentes-y-session_ended)):
  las consultas filtran al instante y `SessionEndReconciliationJob` cierra con `SESSION_ENDED`
  cada minuto. Límites de esa vía:
  - una jornada en `CLOSING` (cerrada pero sin finalizar) sigue vigente hasta que se finaliza o el
    operario abre otra;
  - el cierre guardado llega con hasta un minuto de retraso;
  - el costo es una consulta a Personal Dosimetry por operario con evaluación activa.

  La vía limpia sería que el relay de Personal Dosimetry entregue también `ExposureSessionClosed` a
  este contexto (un `ExposureSessionClosedCommand` propio, como ya hace con los avisos). Requiere un
  cambio en `DosimetryOutboxRelay` (Matias); el filtro seguiría igual como respaldo.
- **404 antes que 403.** En `assessments/{id}` y `projections/workers/{workerId}`, un id inexistente
  da 404 antes de mirar la organización: un usuario de otra organización puede distinguir "no
  existe" de "no es tuyo".
- **Zona desde Personal Dosimetry.** Llega nula. Sin `ShiftUpdated` no hay candidatos de rotación.
- **Eventos `PredictiveRiskDetected` antiguos.** Los ya marcados por versiones anteriores del relay
  tienen `publishedAt`, igual que los nuevos: el significado ("despachado sin consumidor") no cambió.
