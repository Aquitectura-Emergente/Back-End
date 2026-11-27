# Personal Dosimetry & Exposure

Implementación local del contexto de dosimetría personal. El módulo está en
com.emsafe.personaldosimetry, con capas domain, application, interfaces e infrastructure.

## Cálculo y reglas

Se implementa el método de demostración MAGNETIC_TIME_INDEX_V1 aprobado para el prototipo:

- Contribución = campo medio del intervalo en microtesla × duración en segundos.
- Unidad del acumulado: uT*s. No es un límite normativo ni una dosis certificada.
- Se usa BigDecimal sin redondear contribuciones. Solo el porcentaje visible se redondea
  a dos decimales con HALF_UP.
- La advertencia se genera al superar el ratio configurado; la excedencia, al superar
  el límite. La igualdad exacta no dispara el cruce. Se compara antes del redondeo.
- La identidad de captura es organizationId + deviceId + bootId + sequence.
  Reenviar la misma captura es idempotente; reutilizar su identidad con otros datos da 409.
- Los intervalos aceptados no se superponen. Pueden recibirse fuera de orden.
- Las fechas se expresan en UTC y admiten hasta microsegundos; fieldMicrotesla admite
  seis decimales. Los datos que perderían precisión al persistir se rechazan.

## Identidad y ámbito de acceso

Se reutilizan las cuentas activas de IAM y los dispositivos de IoT mediante
ExistingPlatformDirectory. No se cambian los roles generales de la plataforma.

El organizationId es el ID de una cuenta CLIENT activa con clientType = company.
El dosímetro debe estar asignado a esa cuenta. Dentro de este contexto, un administrador
concede la membresía SSO u OPERATOR para una empresa a cuentas elegibles:
`WORKER` puede recibir `OPERATOR`, `SSO_RESPONSIBLE` puede recibir `SSO`, y las cuentas
`CLIENT` activas se siguen admitiendo para datos de demostración y pruebas.

- ADMIN concede membresías y puede operar entre empresas.
- SSO configura límites y administra jornadas de su empresa.
- OPERATOR consulta su propio registro e histórico.
- Una cuenta sin membresía no accede al módulo.
- La organización de la jornada se obtiene del dispositivo registrado, no de un
  organizationId enviado en la petición de apertura.
- La reasignación de una membresía existente se rechaza: requiere un flujo explícito
  de transferencia para no exponer históricos de otra empresa.

Los endpoints de este contexto requieren JWT. El endpoint heredado `/api/v1/readings`
también exige sesión autenticada desde el ajuste de Device Management (2026-11-23).

## API

Prefijo: /api/v1/personal-dosimetry. Las respuestas reutilizan ApiResponse y PageResponse.

| Método | Ruta relativa | Acceso |
| --- | --- | --- |
| POST | /memberships | ADMIN |
| POST | /organizations/{organizationId}/limits | SSO de la empresa o ADMIN |
| GET | /organizations/{organizationId}/limits | SSO de la empresa o ADMIN |
| POST | /sessions | SSO de la empresa o ADMIN |
| GET | /sessions/{id} | Operario propietario, SSO de la empresa o ADMIN |
| POST | /sessions/{id}/readings | SSO de la empresa o ADMIN; entrada para integración autorizada |
| GET | /sessions/{id}/readings | Operario propietario, SSO de la empresa o ADMIN |
| POST | /sessions/{id}/close | SSO de la empresa o ADMIN |
| POST | /sessions/{id}/finalize | SSO de la empresa o ADMIN |
| GET | /workers/me/exposure | Última jornada visible de la cuenta |
| GET | /workers/{workerId}/sessions | Operario propietario, SSO de la empresa o ADMIN |
| GET | /outbox | ADMIN |
| POST | /outbox/{id}/ack | ADMIN |
| GET | /audit | ADMIN |

Los listados aceptan page (desde 0) y size (1 a 200).

Se devuelve 401 sin autenticación; 403 fuera del ámbito permitido; 404 para un recurso
inexistente; 409 ante conflicto de estado, identidad o concurrencia; 422 para una regla
de dominio inválida y 400 ante formato o validación de la petición inválidos.

### Secuencia de uso

Primero se necesitan cuentas y un dispositivo registrados en los módulos existentes.
Los IDs siguientes son ilustrativos y deben sustituirse por IDs de esas cuentas y dispositivo.

1. ADMIN concede las membresías en dos peticiones POST /memberships; organizationId identifica la empresa:

~~~json
{"userId": 21, "organizationId": 10, "role": "SSO"}
{"userId": 22, "organizationId": 10, "role": "OPERATOR"}
~~~

2. SSO configura un límite de demostración para el puesto. El historial conserva las versiones:

~~~json
{
  "jobPositionId": 1,
  "limitIndex": 100,
  "warningRatio": 0.8,
  "effectiveFrom": "2026-11-10T00:00:00Z",
  "reference": "DEMO-INDEX"
}
~~~

3. POST /sessions abre una jornada. startedAt es opcional y nunca puede estar en el futuro:

~~~json
{"workerId": 22, "deviceId": 30, "jobPositionId": 1, "startedAt": "2026-11-10T12:00:00Z"}
~~~

4. POST /sessions/{id}/readings incorpora una captura ya validada por el flujo de ingesta:

~~~json
{
  "deviceId": 30,
  "bootId": "boot-20261110-a",
  "sequence": 1,
  "fieldMicrotesla": 10,
  "unit": "uT",
  "capturedFrom": "2026-11-10T12:00:00Z",
  "capturedTo": "2026-11-10T12:00:08Z"
}
~~~

Esa captura aporta 80 uT*s. Los instantes deben pertenecer a la jornada y no estar en el futuro.
La lectura ambiental existente del Edge no se convierte automáticamente a este contrato:
necesita identidad de arranque, secuencia e intervalo. Una medida instantánea no se interpreta
silenciosamente como promedio de un intervalo.

5. POST /sessions/{id}/close con {"endedAt":"2026-11-10T12:00:10Z"} inicia CLOSING y libera
la asignación activa. Sin endedAt se usa la hora del servidor. Aún se aceptan capturas
del intervalo de la jornada.

6. POST /sessions/{id}/finalize con {"deliveryConfirmed":true} representa una confirmación
explícita del envío pendiente por el flujo autorizado del prototipo. Con false, se exige
que hayan pasado 30 minutos desde endedAt (`reconciliationDeadline`). Además, la tarea
programada `DosimetryReconciliationJob` (cada 60 s por defecto, en lotes de 50; configurable
con `emsafe.personal-dosimetry.reconciliation-enabled`, `reconciliation-delay-ms` y
`reconciliation-batch-size`) finaliza automáticamente con `deliveryConfirmed=false` las
jornadas `CLOSING` cuyo `reconciliationDeadline` ya venció, emitiendo `ExposureSessionClosed`
(sin `finalizedBy`, omitido por `non_null`).

CLOSED congela acumulado, límite y cobertura. Las capturas nuevas del intervalo que llegan
después se conservan como LATE y no cambian ese resultado. La confirmación de entrega no
garantiza cobertura completa: coverageComplete se calcula con la duración exacta, no con
el porcentaje redondeado.

## Persistencia y eventos

- `V16__personal_dosimetry.sql` añade las cinco tablas iniciales del módulo en MySQL.
- `V17__personal_dosimetry_access_audit.sql` añade la auditoría de accesos denegados.
- `V26__personal_reading_saturation.sql` añade la bandera `saturated` en lecturas y el
  contador `out_of_range_readings` en jornadas.
- `V30__personal_exposure_sessions_ended_index.sql` añade los índices
  `ix_pd_session_org_state_ended (organization_id, state, ended_at)` para analítica de
  jornadas cerradas y `ix_pd_session_reconciliation (state, reconciliation_deadline)` para
  la finalización programada de jornadas en reconciliación.

En `main`, `V13` corresponde a `user_organization` y `V19–V21` a Occupational Health
(`V14–V15` no existen en `main`).

Cada jornada conserva el ID (`limitVersionId: Long`) y los valores de su versión de límite.
Los cambios de configuración de límites (`POST /organizations/{id}/limits`) afectan a nuevas
jornadas; una jornada abierta solo cambia de puesto y límite aplicable cuando recibe un
`ShiftUpdated` desde Occupational Health. Las lecturas se almacenan separadas del agregado
para no cargar el histórico completo en cada ingesta.

La aplicación bloquea la jornada (`PESSIMISTIC_WRITE`) durante la ingesta y los cambios de
puesto. Las restricciones únicas de la base de datos protegen la asignación activa y la
identidad de cada lectura.

La modificación del estado y la inserción de sus eventos en `personal_dosimetry_outbox`
ocurren en la misma transacción. Todos los payloads incluyen `"schemaVersion": 1` y
`"organizationId": Long`, y unifican `limitVersionId` como **`Long`** en la outbox, la API
REST y los contratos en proceso hacia Occupational Health y AI Forecasting.

### Los cuatro eventos que entrega `DosimetryOutboxRelay`

El relay en proceso (`DosimetryOutboxRelay`, cada 30 s por defecto) lee los eventos con
`publishedAt IS NULL` de los cuatro tipos entregables, publica los contratos en proceso y
marca `publishedAt`:

1. **`ExposureSessionOpened` → AI Forecasting (`UpsertExposureSnapshotCommand`)**
   - Payload en outbox:
     `sessionId: String (UUID)`, `workerId: Long`, `deviceId: Long`, `limitVersionId: Long`,
     `positionId: Long`, `accumulatedIndex: BigDecimal ("0")`, `limitIndex: BigDecimal`,
     `startedAt: Instant (ISO-8601 UTC)`, `openedBy: Long`.
   - Traducción del relay: crea `UpsertExposureSnapshotCommand` con `zoneId = null` (la zona
     llega por `ShiftUpdated`) y `capturedAt = startedAt`.

2. **`ExposureWarningRaised` → Occupational Health (`ExposureWarningRaised`) y AI Forecasting (`ExposureWarningRaisedCommand`)**
   - Payload en outbox:
     `sessionId: String`, `workerId: Long`, `readingId: String` *(opcional si el cruce proviene de `ShiftUpdated`)*,
     `accumulatedIndex: BigDecimal`, `limitIndex: BigDecimal`, `limitVersionId: Long`,
     `capturedAt: Instant`, `detectedAt: Instant`, `methodId: "MAGNETIC_TIME_INDEX_V1"`,
     `unit: "uT*s"`.
   - Traducción del relay: primero publica `occupationalhealth.application.events.inbound.ExposureWarningRaised`
     (`dose = accumulatedIndex`, `limit = limitIndex`, `limitVersionId: Long`, fechas en
     `LocalDateTime` UTC) para disparar la alerta preventiva `warning` al operario; luego
     publica `ExposureWarningRaisedCommand` (`limitVersionId: Long`, `capturedAt: Instant`)
     hacia AI Forecasting.

3. **`OccupationalLimitExceeded` → Occupational Health (`OccupationalLimitExceeded`)**
   - Payload en outbox: idéntico al de `ExposureWarningRaised`.
   - Traducción del relay: publica `occupationalhealth.application.events.inbound.OccupationalLimitExceeded`
     (`segmentId = null`, `dose = accumulatedIndex`, `limit = limitIndex`,
     `limitVersionId: Long`, `methodId`, `unit`, `capturedAt`/`detectedAt` en `LocalDateTime`
     UTC). En OH lo consumen `OccupationalLimitExceededEventHandler` (abre propuesta de
     mitigación) y `ExposureAlertEventHandler` (crea alerta `danger` con escalamiento al SSO).

4. **`ExposureSessionClosed` → Occupational Health (`ExposureSessionClosed`)**
   - Payload en outbox:
     `sessionId: String`, `workerId: Long`, `accumulatedIndex: BigDecimal`,
     `limitIndex: BigDecimal`, `limitVersionId: Long`, `coveragePercentage: BigDecimal`,
     `coverageComplete: boolean`, `finalizedBy: Long`, `finalizedAt: Instant`,
     `methodId: "MAGNETIC_TIME_INDEX_V1"`, `unit: "uT*s"`.
   - Traducción del relay: publica `ExposureSessionClosed` con `snapshotId = null`,
     `finalDose = accumulatedIndex`, `limit = limitIndex`, `coverage = "<pct>% (completa|incompleta)"`
     y `finalizedAt` en `LocalDateTime` UTC (con respaldo en `occurredAt` para eventos
     anteriores al 2026-11-12).

### Otros eventos en `personal_dosimetry_outbox`

- `DosimetryMembershipGranted` (`userId, role, grantedBy`) y `OccupationalLimitConfigured`
  (`limitVersionId, version, positionId, changedBy, methodId`): auditoría de configuración.
- `ExposureSessionCloseRequested` (`sessionId, endedAt, requestedBy`) y
  `PersonalReadingArrivedLate` (`sessionId, readingId, capturedAt, receivedAt`): quedan
  pendientes sin bloquear a los cuatro tipos entregables (la consulta del relay filtra por
  `eventType IN DELIVERABLE`).

Un consumidor externo autorizado también puede consultar `GET /outbox` y confirmar con
`POST /outbox/{id}/ack` deduplicando por `eventId`.

## Auditoría y retención

El filtro del contexto registra los rechazos de autenticación/autorización 401 y 403
que pasan por su cadena de seguridad. Conserva la cuenta autenticada (null si es anónima),
el método HTTP, la ruta sin parámetros de consulta, el estado HTTP y el instante UTC.
No almacena cabeceras, tokens ni cuerpos de petición. GET /audit pagina los registros
y exige ADMIN; un SSO no puede consultar la auditoría global de otras empresas.

El registro usa una transacción independiente para sobrevivir al rollback de la operación
rechazada. Si la base está indisponible, el acceso sigue rechazado y se emite un error
operacional en el log; no se garantiza persistencia de esa evidencia durante la caída.

La limpieza programada opera solo sobre auditorías y eventos de outbox ya confirmados.
Ambos plazos valen 0 por defecto: no se elimina nada hasta configurar una política.
Los siguientes son los valores iniciales, no una recomendación de plazos:

~~~properties
emsafe.personal-dosimetry.retention.audit-days=0
emsafe.personal-dosimetry.retention.published-event-days=0
emsafe.personal-dosimetry.retention.batch-size=500
emsafe.personal-dosimetry.retention.cleanup-interval=PT1H
~~~

Cada ejecución elimina como máximo batch-size registros por categoría. El plazo de los
eventos empieza en publishedAt (confirmación del consumidor). Los eventos pendientes,
las jornadas, las lecturas y las versiones de límites se conservan. Los plazos concretos
del entorno compartido y la retención de evidencia de exposición requieren una decisión
del proyecto; no se ha inventado un plazo normativo.

## Pruebas y revisión

Con Java 17, desde la raíz del repositorio, en Windows:

~~~powershell
$env:JAVA_HOME = 'RUTA_DE_TU_JDK_17'
.\mvnw.cmd test
~~~

Para ejecutar solo las pruebas del contexto:

~~~powershell
.\mvnw.cmd '-Dtest=ExposureIndexTest,ExposureSessionTest,DosimetryActorTest,PersonalDosimetryApiTest,DosimetryRestartRecoveryTest,MysqlTestDatabaseTest' test
~~~

Las pruebas de dominio comprueban fórmula, redondeo, fronteras, cierre, cobertura y permisos.
Las pruebas de API levantan la aplicación completa con H2 en modo MySQL y un reloj fijo.
Comprueban HTTP, JWT obligatorio, aislamiento por empresa y operario, membresías, versiones
de límite, duplicados, intervalos superpuestos, datos atrasados, concurrencia y rollback si
la outbox falla. También verifican la auditoría independiente, la consulta administrativa,
el fallo del almacenamiento de auditoría y los límites de la limpieza configurada.
La prueba de arranque existente conserva su inicialización de datos:
solo se adapta la palabra VALUE de H2 al esquema heredado.

La verificación local del 11 de noviembre de 2026 terminó con 57 pruebas, 0 fallos,
0 errores y 0 omitidas: 21 de dominio, 28 de API/persistencia/carga, una de recuperación,
seis del guardián de base MySQL y una de arranque. El perfil MySQL no se ejecutó.

La prueba de carga usa seis productores concurrentes sobre una jornada: 240 capturas
únicas y 80 reenvíos. Exige 240 lecturas persistidas, acumulado exacto de 120 uT*s y un
solo evento por umbral. Genera target/dosimetry-load-PersonalDosimetryApiTest.json con
duración, entregas por segundo y percentiles. Mide transacciones del servicio en el
equipo local; no mide tráfico HTTP, el hardware ni la capacidad del despliegue.

La prueba de recuperación cierra el contexto de Spring, su pool y EntityManagerFactory,
y vuelve a abrirlos contra una base H2 en disco temporal. Verifica el acumulado, las
lecturas, la auditoría y los eventos pendientes, y reenvía una captura sin duplicar su
contribución. No simula un corte eléctrico ni reinicia el servidor de base de datos.

El consumidor de prueba demuestra reentrega con el mismo eventId cuando falta /ack
y deduplicación en el receptor simulado. No implementa almacenamiento durable del
consumidor de Alerts ni comunicación real entre contextos.

### MySQL: preparado, pendiente de ejecutar

La validación real con MySQL quedó aplazada por falta de espacio en C:, a petición de
Matias. No se presenta la suite H2 como validación de las migraciones MySQL.

Cuando haya al menos 1 GB libre, con MySQL Server 8 y Java 17 instalados en Windows:

~~~powershell
$env:JAVA_HOME = 'RUTA_DE_TU_JDK_17'
.\scripts\Test-PersonalDosimetryMySql.ps1
~~~

Si MySQL está en otra carpeta, pasar -MySqlHome 'RUTA_DE_MYSQL_SERVER_8'. El script crea
una instancia temporal en target, enlazada a 127.0.0.1:13306, con base y contraseña
aleatorias. Ejecuta el perfil mysql-it, apaga únicamente su proceso y retira sus datos
temporales. No usa la base emsafe_db ni depende de Docker. Conserva el log de pruebas
target/mysql-verification.log y el del servidor target/mysql-test-server.log.

El perfil vuelve a ejecutar el mismo contrato de API, persistencia y carga contra MySQL,
con Flyway habilitado y Hibernate en validate. Incluye una recuperación mediante dos
contextos de aplicación contra esa base y una comprobación explícita de V16/V17.
Las clases MysqlIT no se ejecutan con el comando test normal.

Para una base de pruebas ya creada y descartable:

~~~powershell
$env:DOSIMETRY_MYSQL_URL = 'jdbc:mysql://127.0.0.1:13306/emsafe_pd_test?serverTimezone=UTC'
$env:DOSIMETRY_MYSQL_USERNAME = 'USUARIO_DE_PRUEBAS'
$env:DOSIMETRY_MYSQL_PASSWORD = 'CONTRASENA_DE_PRUEBAS'
.\mvnw.cmd -Pmysql-it test
~~~

La suite modifica datos y exige un nombre emsafe_pd_test o emsafe_pd_test_<sufijo>.
Debe usarse una base nueva para cada ejecución. Las pruebas del guardián de URL se
ejecutan con H2; la ejecución completa del perfil MySQL sigue pendiente.

Las pruebas H2 no certifican la migración en un servidor MySQL, los objetivos de carga o
disponibilidad, ni una integración con el hardware.

## Integración con Occupational Health (2026-11-22)

Cambios que hizo Fabricio (Occupational Health) dentro de este módulo, coordinados con el
plan del equipo. No cambian ningún payload ni ninguna tabla:

- **`application/ExposureQueryFacade.java` (nuevo).** Consulta pública de solo lectura:
  `currentExposure(organizationId, workerId)` devuelve la jornada `OPEN` más reciente del
  operario con `accumulatedIndex`, `limitIndex`, `unit = "uT*s"`, `methodId =
  "MAGNETIC_TIME_INDEX_V1"`, `jobPositionId` y `latestCaptureTo`; vacío si no tiene jornada
  abierta. La usa `PersonalDosimetryExposureAdapter` de Occupational Health. Cambiar su firma
  rompe a OH: avisar antes.
- **`ExposureSessionRepository`**: nuevo método
  `findFirstByOrganizationIdAndWorkerIdAndStateOrderByStartedAtDesc` (lo usa la fachada).
- **`DosimetryOutboxRelay`**: `ExposureWarningRaised` ahora se entrega **también** a
  Occupational Health (record `occupationalhealth.application.events.inbound.ExposureWarningRaised`),
  además del `ExposureWarningRaisedCommand` de AI Forecasting. Se entrega primero a OH; si AI
  falla, el evento queda pendiente y OH descarta la reentrega por su inbox.

## Integración Partnership y contratos unificados (2026-11-24)

- **Unificación de `limitVersionId` a `Long`.** Tanto en `personal_dosimetry_outbox` como en
  `DosimetryOutboxRelay`, `OccupationalLimitExceeded` y `ExposureWarningRaised` (Occupational
  Health) y `ExposureWarningRaisedCommand` (AI Forecasting), `limitVersionId` viaja como
  `Long` (el relay admite también cadenas numéricas heredadas al leer JSON antiguo).
- **Consumo de `ShiftUpdated` (`infrastructure/DosimetryShiftUpdatedEventListener.java`).**
  Escucha `occupationalhealth.application.events.ShiftUpdated` con
  `@TransactionalEventListener(AFTER_COMMIT)` y `@Transactional(REQUIRES_NEW)`:
  - Localiza la jornada por `sessionId` o, si viene nulo/vacío, busca la jornada `OPEN` del
    operario en la organización; si no existe jornada abierta en dosimetría, ignora el evento
    sin afectar la transacción ya confirmada en Occupational Health.
  - Conserva `accumulatedIndex` (`uT*s`), actualiza `jobPositionId`, `limitVersionId`,
    `limitIndex` y `warningRatio` al límite vigente del `newPositionId`, y reevalúa
    `warningRaised` y `limitExceeded` frente al nuevo límite (si el puesto destino tiene un
    límite mayor, limpia las banderas para volver a avisar si más tarde se supera; si es más
    estricto y el acumulado ya lo cruza, emite los eventos de umbral correspondientes).
  - Deduplica reentregas por `(sessionId/workerId, eventId)`. El historial de tramos por
    puesto sigue residiendo en `WorkerShift.assignments` de Occupational Health (versión
    mínima sin tabla `SessionPositionSegment`).

## Integraciones siguientes

- ~~Conectar el publicador de outbox con Alerts~~: hecho el 2026-11-22 (`ExposureAlertEventHandler`).
- ~~Unificar `limitVersionId` y documentar los cuatro payloads del relay~~: hecho el 2026-11-24.
- ~~Incorporar `ShiftUpdated` (versión mínima)~~: hecho el 2026-11-24 (`DosimetryShiftUpdatedEventListener`).
- Opcional futuro: persistir segmentos por puesto (`SessionPositionSegment`) dentro de dosimetría si se requiere desglose por tramo además del de `WorkerShift`.
- Acordar los plazos de retención del entorno compartido y de la evidencia de exposición.
- Ejecutar la validación MySQL preparada (`scripts/Test-PersonalDosimetryMySql.ps1`) cuando se disponga de instancia local con espacio libre.
- Repetir carga y recuperación con HTTP en el entorno de despliegue.

El estado actual incluye jornadas, ingesta vía ACL desde Device Management y vía REST,
cambio de puesto por `ShiftUpdated`, fachada `ExposureQueryFacade`, cálculo en `uT*s`,
relay de outbox en proceso, auditoría y limpieza configurable.
