# AI Forecasting & Analytics

Bounded context `com.emsafe.aiforecasting`. Proyecta el índice de exposición magnética acumulado
de cada operario hasta el fin de su turno, prioriza a los operarios por urgencia y recomienda una
rotación de puestos o la reprogramación de la tarea. Redacta además una explicación en lenguaje
natural, con Gemini o con una plantilla.

Este contexto **no decide** si un operario excedió su límite: esa decisión es de Personal Dosimetry.
Tampoco ejecuta recomendaciones: las publica para Occupational Health, donde decide el SSO.
Todo lo que produce es una proyección (`isProjection = true`).

La exposición es un índice acumulado en **uT·s** (`MAGNETIC_TIME_INDEX_V1`), no una dosis.

## Contenido

- [Modelo](#modelo)
- [Reglas de cálculo](#reglas-de-cálculo)
- [API](#api)
- [Eventos salientes](#eventos-salientes)
- [Propiedades](#propiedades)
- [Persistencia](#persistencia)
- [Prueba manual en Swagger](#prueba-manual-en-swagger)
- [Pruebas automatizadas](#pruebas-automatizadas)
- [Integración pendiente](#integración-pendiente)

## Modelo

### Snapshot de exposición (modelo de lectura local)

`af_worker_exposure_snapshot` guarda una fila por sesión abierta, con estos campos:
`organizationId`, `workerId`, `sessionId`, `zoneId`, `positionId`, `accumulatedIndex`,
`limitIndex`, `sessionStartedAt`, `plannedShiftEnd`, `capturedAt` y `updatedAt`.

Existe porque ningún contexto entrega hoy todos los datos que necesita la proyección. Se alimenta
por el inbox (`exposure-snapshot`, `exposure-warning-raised` y `shift-updated`) con estas reglas:

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
- **Motivos de cierre** (`CloseReason`): `SUPERSEDED` o `SESSION_ENDED`.

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
  `gemini.api-key` y `gemini.model`, con un `RestTemplate` propio y timeout `llm.timeout-ms`.
  - El prompt va en español y solo incluye IDs, porcentajes, horas y el tipo de recomendación,
    nunca nombres ni datos personales.
  - La key viaja en la cabecera `x-goog-api-key`, no en la URL.
- **Fallback.** `FallbackMessagePolicy` genera un texto con plantilla fija y las cifras. Se usa con
  el LLM deshabilitado (valor por defecto), cuando no hay key, ante error o timeout, o si la
  respuesta viene vacía.
- **La evaluación nunca falla por el LLM.** El campo `redaction.generatedBy` indica el origen:
  `LLM` o `FALLBACK`.

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

| Rol | Inbox y outbox | Consultas |
| --- | --- | --- |
| ADMIN | sí | todas las organizaciones |
| CLIENT con `clientType = company` | no | solo su organización (`organizationId` = id de su usuario) |
| Otros (TECHNICIAN, CLIENT individual) | no | no |

Todo el control de acceso está en `ForecastingAccessPolicy`. La política se aplica en tres capas:

1. `SecurityConfig` exige un JWT válido en `/api/v1/ai-forecasting/**`.
2. Un `HandlerInterceptor` exige ADMIN en inbox y outbox **antes** de leer el cuerpo. Así, un
   no-ADMIN recibe `403` aunque el cuerpo sea inválido. `@PreAuthorize` no basta para esto, porque
   la lectura del JSON y `@Valid` ocurren antes de invocar el método protegido.
3. `@PreAuthorize("hasRole('ADMIN')")` en esos dos controllers, como segunda capa.

### Códigos de error

| Código | Cuándo |
| --- | --- |
| 400 | JSON mal formado; campo obligatorio ausente; más de 6 decimales en un índice; fecha o enum inválidos; `page` o `size` fuera de rango; `critical-workers` sin `organizationId` para ADMIN |
| 403 | Sin token, token inválido o fuera de ámbito (el proyecto responde 403 en todos estos casos) |
| 404 | La evaluación, la evaluación activa del operario o el evento del outbox no existen |
| 409 | Aviso o cambio de turno de una sesión sin snapshot; sesión de otro operario u organización; escrituras concurrentes sobre la misma sesión o evento |
| 422 | Regla de dominio inválida: `elapsed ≤ 0`, `limitIndex ≤ 0`, índice negativo, método o unidad no soportados |

Los DTOs solo validan formato y presencia (`400`); las reglas de dominio responden `422`.
`AiForecastingExceptionHandler`, acotado al paquete, traduce `422`, `409` y los errores de formato;
`404` y `403` los resuelve `GlobalExceptionHandler`.

## Eventos salientes

Se escriben en `af_outbox` en la misma transacción que el cambio de estado del agregado. Se leen con
`GET /outbox` y se confirman con `POST /outbox/{eventId}/ack`.

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

### `PredictiveRiskDetected`

Se emite con criticidad HIGH o CRITICAL:

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
| `emsafe.ai-forecasting.model-id` | `LINEAR_RATE` | Se guarda en cada evaluación |
| `emsafe.ai-forecasting.model-version` | `1` | Código resultante: `LINEAR_RATE_V1` |
| `emsafe.ai-forecasting.llm.enabled` | `false` | `false` = solo plantilla, sin red (siempre en tests) |
| `emsafe.ai-forecasting.llm.timeout-ms` | `5000` | Timeout de conexión y de lectura con Gemini |
| `emsafe.ai-forecasting.seed-demo` | `false` | Crea 5 operarios de demostración al arrancar |

El seeder de demostración usa la primera cuenta CLIENT `company` (la de menor id). Crea 5
snapshots en la zona `DEMO-ZONE-A`, con tasas de 0.5, 0.35, 0.2, 0.1 y 0.05 uT·s/s. Pasa por el
mismo handler del inbox, así que reiniciar el mismo día no tiene efecto.

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

```bash
JAVA_HOME="C:/Program Files/Java/jdk-17" ./mvnw test
```

| Capa | Clases |
| --- | --- |
| Dominio (sin Spring) | `ExposureProjectionServiceTest`, `RotationCandidateSelectorTest`, `CriticalityAssessmentServiceTest`, `RotationRecommendationServiceTest`, `PreventiveAssessmentTest`, `FallbackMessagePolicyTest`, `WorkerExposureSnapshotTest`, `AssessmentPriorityTest`, `ValueObjectsTest` y `DomainPurityTest` (falla si el dominio importa Spring, JPA o Lombok) |
| Aplicación, con fakes en memoria | `TriggerAssessmentHandlerTest`, `UpsertExposureSnapshotHandlerTest`, `ShiftUpdatedHandlerTest`, `QueryHandlersTest`, `OutboxHandlersTest`, `ForecastingAccessPolicyTest` |
| Infraestructura | `AiForecastingPersistenceTest` (`@DataJpaTest` sobre H2), `GeminiRedactionAdapterTest` (`MockRestServiceServer`: éxito, timeout, vacío, error), `ForecastingPropertiesTest`, `AiForecastingDemoSeederTest` |
| REST | `AiForecastingControllerTest` (contrato web y Swagger) y `AiForecastingApiIntegrationTest` (JWT real por login: acceso, flujo, contrato de Occupational Health, ack, duplicados y 400/404/409/422) |

La correspondencia entre la migración V18 y las entidades se verificó arrancando con el perfil
`dev` contra MySQL 8 y `ddl-auto=validate`.

## Integración pendiente

Este contexto compila y funciona sin Personal Dosimetry, Occupational Health ni la rama
`feature/iam`. Al integrarlos, estos son los puntos a cambiar.

### Personal Dosimetry

- **Origen de los avisos.** Hoy `ExposureWarningRaised` llega por `POST /inbox/exposure-warning-raised`.
  Cuando la rama esté en `develop`, se necesita un relay que lea el outbox de Personal Dosimetry,
  invoque `TriggerAssessmentHandler` con el mismo `eventId` y haga `ack` en ese outbox. La
  idempotencia por `af_processed_message` ya cubre los reintentos.
- **Origen de los snapshots.** El inbox `exposure-snapshot` también puede alimentarse desde Personal
  Dosimetry, con esta correspondencia: `ExposureSession.startedAt` → `sessionStartedAt`,
  `jobPositionId` → `positionId` y `limitVersionId` → `limitVersionId`. Personal Dosimetry no
  conoce la zona: `zoneId` seguirá llegando por `shift-updated`.
- **Nada cambia en el dominio.** El contrato de `ExposureWarningRaisedCommand` ya coincide con el
  evento (`methodId`, `unit`, `limitVersionId`).

### Occupational Health

- **Publicación de eventos.** Hoy los eventos quedan en `af_outbox` y se consultan con
  `GET /outbox`. Hay que añadir un publicador que lea los pendientes, envíe el `payload` de
  `RotationSuggested` a `POST /api/v1/occupational-health/inbox/rotation-suggested` (o lo publique
  en proceso con `ApplicationEventPublisher`) y llame a `ack`. El payload ya es compatible.
- **Sobre de eventos.** El inbox de Occupational Health hoy crea su propio sobre
  (`EventEnvelope.of`) y pierde nuestro `eventId`/`correlationId`. Para conservar la trazabilidad,
  ese contexto debería aceptar el sobre entrante.
- **Cambios de turno.** Los `WorkerShift` de Occupational Health son la fuente natural de
  `shift-updated` (zona, puesto y fin de turno). Se conectan con un adaptador que invoque
  `ShiftUpdatedHandler`.

### IAM (`feature/iam`)

- **Directorio de actores.** Hay que cambiar solo `IamForecastingActorDirectory`. Con la columna
  `users.organization_id` (V13 de IAM), un `SSO_RESPONSIBLE` representa a la organización
  `user.organizationId`.
- **Política de acceso.** En `ForecastingAccessPolicy` hay que habilitar `SSO_RESPONSIBLE` para las
  consultas de su organización. El inbox y el outbox siguen siendo solo de ADMIN.
- **Cuentas de empresa.** La convención actual, en la que la organización es una cuenta CLIENT
  `company` y su id es el del usuario, es compatible: IAM también referencia la organización como
  `users(id)` de la empresa.

### Orden de migraciones Flyway

`V18__ai_forecasting.sql` debe aplicarse **después** de V13–V17:

| Versión | Rama |
| --- | --- |
| V13 | `feature/iam` (`user_organization`) **y** `feature/occupational-health-mitigation` (`occupational_health`) |
| V14–V15 | `feature/occupational-health-mitigation` |
| V16–V17 | `feature/personal-dosimetry` |
| V18 | `feature/ai-forecasting` |

- **Colisión en V13.** `feature/iam` y `feature/occupational-health-mitigation` usan las dos V13.
  Flyway falla con dos migraciones de la misma versión, así que una de ellas tiene que renumerarse
  antes de integrar. No afecta a V18.
- **Bases locales que ya aplicaron V18.** Por ejemplo, una base `dev` arrancada desde esta rama
  queda en V18 sin V13–V17, y Flyway rechazará esas migraciones por estar fuera de orden. Hay tres
  opciones:
  1. recrear la base local (es lo recomendado: `DataInitializer` la vuelve a sembrar);
  2. arrancar una vez con `spring.flyway.out-of-order=true`;
  3. borrar las tablas `af_*` y la fila 18 de `flyway_schema_history` para que V18 se aplique de
     nuevo al final.
- **En producción**, basta con integrar las ramas en orden de versión: V18 se aplica al final.

### Conflicto en `SecurityConfig`

Las tres ramas modifican el mismo bloque `authorizeHttpRequests` alrededor de la regla
`.requestMatchers("/api/v1/**").permitAll()`, así que al fusionarlas aparecerá un conflicto textual
en `SecurityConfig`:

| Rama | Cambio |
| --- | --- |
| `feature/personal-dosimetry` | `"/api/v1/personal-dosimetry/**"` autenticado, un `HttpStatusEntryPoint(401)` solo para su ruta y un filtro de auditoría |
| `feature/occupational-health-mitigation` | `"/api/v1/occupational-health/**"` con `hasAnyRole("SSO", "WORKER", "ADMIN")` |
| `feature/iam` | Reglas de `/api/sso/**` y de perfil con los roles `WORKER` y `SSO_RESPONSIBLE` |
| `feature/ai-forecasting` | `"/api/v1/ai-forecasting/**"` autenticado |

Reglas para resolver el conflicto:

1. **Conservar todas las reglas específicas.** Cada `"/api/v1/<contexto>/**"` debe quedar
   **antes** de `.requestMatchers("/api/v1/**").permitAll()`. Si una queda después, ese contexto
   queda abierto sin autenticación.
2. **El 401 de Personal Dosimetry no cambia AI Forecasting.** Su entry point está acotado a su
   ruta: AI Forecasting seguirá respondiendo `403` sin token, y
   `AiForecastingApiIntegrationTest` lo verifica.
3. **Revisar el nombre del rol SSO.** Occupational Health usa el rol `SSO` e IAM define
   `SSO_RESPONSIBLE`. Hay que alinearlos al integrar; no afecta a AI Forecasting.
4. **Volver a correr las pruebas.** Después de resolver el conflicto, corre `./mvnw test`.
   `AiForecastingApiIntegrationTest` falla si `/api/v1/ai-forecasting/**` queda sin autenticar o
   si `/api/v1/readings` (IoT, público) deja de serlo por error.
