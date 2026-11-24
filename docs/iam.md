# IAM y seguridad — EMSafe Back-End

Documento del contexto **IAM** (`com.emsafe.iam`) y de la configuración de seguridad
compartida (`com.emsafe.shared.config`). Describe **lo que hace el código hoy**
(rama `feature/iam`, 2026-11-24), no lo que propone el informe. Donde el informe dice otra
cosa, se indica.

Código de referencia:

| Pieza | Archivo |
|---|---|
| Roles | `iam/domain/model/Role.java` |
| Cuenta (agregado) | `iam/domain/model/AppUser.java`, `UserStatus.java` |
| Login, refresh, registro | `iam/application/AuthService.java`, `iam/interfaces/rest/AuthController.java` |
| Usuarios | `iam/application/UserService.java`, `iam/interfaces/rest/UserController.java` |
| Panel SSO | `iam/application/SsoService.java`, `iam/interfaces/rest/SsoController.java` |
| JWT | `iam/infrastructure/security/JwtUtil.java`, `JwtAuthFilter.java`, `UserDetailsServiceImpl.java` |
| Secreto JWT | `iam/infrastructure/security/JwtSecretValidator.java`, `JwtSecretFailureAnalyzer.java` |
| Bloqueo por intentos | `iam/domain/services/LoginAttemptService.java` |
| Reglas HTTP | `shared/config/SecurityConfig.java` |
| WebSocket | `shared/config/WebSocketConfig.java`, `StompAuthChannelInterceptor.java` |
| Organización | `db/migration/V13__user_organization.sql` |

---

## 1. Roles

`Role.java` define cinco roles. Es un **contrato congelado**: no se agregan ni renombran.

| Rol | Quién es | Qué puede hacer (según `SecurityConfig` y los servicios) | Cliente que usa |
|---|---|---|---|
| `ADMIN` | Administrador de EMSafe | Todo lo que no tiene regla propia (`anyRequest().hasRole("ADMIN")`): `/api/users/**`, `/api/devices/**`, `/api/alarms/**`, etc. También entra en `/api/tech/**`, `/api/client/**`, Occupational Health, Plant Configuration, acusar y escalar alertas. **No** entra en `/api/sso/**`. | Portal web (y la app, solo para pruebas) |
| `TECHNICIAN` | Técnico de campo | `/api/tech/**`; su propio perfil (`GET /api/users/profile`, `PUT /api/users/{id}`, `PATCH /api/users/{id}/password`, solo sobre su cuenta). | Portal web |
| `CLIENT` | Cliente: empresa u hogar | `/api/client/**` (incluido su perfil, que **no** pasa por `/api/users`). Una cuenta CLIENT `company` es además una **organización** (§3). | App móvil |
| `WORKER` | Operario de una organización industrial | Occupational Health y Plant Configuration (filtrado por organización en cada política de acceso), `POST /api/alarms/*/acknowledge`, `GET /api/alarms/mine` y su propio perfil. | Ver la observación de §2.3 |
| `SSO_RESPONSIBLE` | Responsable de SSO de una organización | `/api/sso/**` (solo él), Occupational Health, Plant Configuration, `POST /api/alarms/*/escalate`, `GET /api/alarms/organization` y su propio perfil. | Portal web |

- `Role.isIndustrial()` es verdadero para `WORKER` y `SSO_RESPONSIBLE`: son las cuentas que
  **deben** tener `organizationId`.
- Spring Security trabaja con las autoridades `ROLE_<ROL>` (`UserDetailsServiceImpl` usa
  `.roles(u.getRole().name())`).
- **No existe `SUPER_ADMIN`**, aunque el informe (§5.1.1) lo menciona.

---

## 2. Cómo viaja el rol

El mismo rol aparece con **tres formatos distintos**. Los tres son contrato.

| Dónde | Formato | Ejemplo | Origen |
|---|---|---|---|
| Respuesta de login (`LoginResponse.role`) | minúsculas | `sso_responsible` | `AuthService.login`: `user.getRole().name().toLowerCase()` |
| Claim `role` del JWT | MAYÚSCULAS | `SSO_RESPONSIBLE` | `JwtUtil.generateToken(..., user.getRole().name(), ...)` |
| `UserDto.role` (`/api/users`, `/api/sso/workers`) | Title case | `SsoResponsible` | `Role.displayName()` |

### 2.1 Qué decide la autorización

El backend **no autoriza con el claim `role` del JWT**. `JwtAuthFilter` valida la firma y la
expiración, toma el `sub` (email) y **recarga la cuenta desde la base** en cada petición
(`UserDetailsServiceImpl`). Las autoridades salen del rol guardado en `users.role`. Por eso:

- si un admin cambia el rol de una cuenta, el cambio rige en la siguiente petición;
- si la cuenta pasa a `pending` o `inactive`, el token deja de servir aunque no haya expirado
  (`disabled` cuando `status` no es `active`; `status` nulo cuenta como activo);
- si la cuenta se borra, el token deja de servir (sin error 500).

El claim `role` lo usan otros componentes como dato informativo
(`JwtUtil.extractRole`).

### 2.2 Portal web (`Front-End_`, `core/guards/auth.guard.ts`, rama `main`)

El portal guarda el `role` de `LoginResponse` (minúsculas) y los guards comparan contra esas
cadenas:

- `adminGuard` → `'admin'`
- `techGuard` → `'technician'`
- `ssoGuard` → `'sso_responsible'` (el ADMIN no entra: no tiene organización y el backend
  le respondería 403)

`isWebRole` (`contexts/iam/domain/auth-user.model.ts`) solo admite `admin`, `technician` y
`sso_responsible`: `client` y `worker` se rechazan en el login del portal. El portal no
decodifica el JWT; lo envía en `Authorization: Bearer` y usa `POST /api/auth/refresh` desde
`core/interceptors/auth.interceptor.ts`.

### 2.3 App móvil (`Mobile_App`, `contexts/iam/application/auth_facade.dart`, rama `main`)

`AuthFacade.login` pasa el `role` de la respuesta a minúsculas y lo valida con
`MobileRole.isAllowed`, que solo admite `client` y `admin`. Guarda en `SessionStore` el
token, el nombre, el email, el rol y el `userId`. El segmento (hogar u operario) **no sale
del rol**: `SegmentProbe` llama a `GET /api/v1/personal-dosimetry/workers/me/exposure` y
decide con el código HTTP (200/404 → operario; 403 → hogar). La app solo decodifica el JWT
para leer `exp` y detectar la sesión vencida (`session_store.dart`); no usa el claim `role`.

> **Observación (no es un cambio de este documento):** con `MobileRole.isAllowed` como está
> en `main` y en `origin/develop`, una cuenta con rol `WORKER` es rechazada por la app, y el
> portal también la rechaza (`isWebRole`). Hoy el "operario" de la app es una cuenta
> `CLIENT` con membresía en Personal Dosimetry. Las cuentas `WORKER` sembradas
> (`operario1@quantumdyn.com`, …) solo pueden usarse directamente contra la API.

---

## 3. Modelo de organización

- **No existe un agregado `Organization`** ni una tabla `organizations`.
- La organización **es la cuenta `CLIENT` de la empresa**: `users.organization_id` es una FK a
  `users.id` (`V13__user_organization.sql`, `ON DELETE RESTRICT`, índice
  `idx_users_organization`).
- `organizationId` de un `WORKER` o `SSO_RESPONSIBLE` = `users.id` del `CLIENT` empresa.
  Es `null` para `ADMIN`, `TECHNICIAN` y `CLIENT`.
- Los identificadores son **`Long`** (autoincremental, `GenerationType.IDENTITY`), **no UUID**
  como plantea el informe.

Reglas que aplica el código:

| Regla | Dónde |
|---|---|
| Al crear un `WORKER` o `SSO_RESPONSIBLE`, `organizationId` es obligatorio y debe ser el id de una cuenta `CLIENT` existente. | `UserService.resolveOrganization` |
| Los demás roles no aceptan `organizationId` (400). | `UserService.resolveOrganization` |
| No se puede borrar una organización que aún tiene cuentas ligadas (400). | `UserService.delete` (`existsByOrganizationId`) |
| El SSO solo ve a los `WORKER` de **su** organización; un operario de otra da 403. | `SsoService` (`sameOrganizationAs`) |
| Una cuenta sin organización no pertenece a ninguna (`belongsTo` es falso), así que nunca ve datos industriales por descuido. | `AppUser.belongsTo` |
| `LoginResponse.organizationId` lleva la organización de la cuenta (o `null`). | `AuthService.login` |

`UpdateUserRequest` no incluye `organizationId` ni `role`: ambos se fijan al crear la cuenta.

---

## 4. `clientType`

`AppUser.clientType` (columna `client_type`, `VARCHAR(20)`, nullable, `AppUser.java:83`) es un
**`String` libre, sin enum y sin validación**: `AuthService.register` y `UserService.update`
guardan lo que llegue.

Valores que **usa realmente** el código:

| Valor | Significado | Dónde se escribe | Dónde se lee |
|---|---|---|---|
| `company` | Cliente empresa = organización | Migración V10 (todos los `CLIENT` existentes pasaron a `company`); `DataInitializer` (Quantum, Harbor, Global Pharma); app móvil y portal al registrar/editar | `IamForecastingActorDirectory` y `AiForecastingDemoSeeder` (AI Forecasting) y `ExistingPlatformDirectory` (Personal Dosimetry), siempre con `equalsIgnoreCase("company")`; portal `isCompanyClient` |
| `individual` | Cliente persona (hogar) | App móvil (`onboarding_flow_store.dart`) y portal (`edit-client`) | Portal (`view-client`, `edit-client`) |
| `null` | Sin tipo (cuentas no CLIENT, o CLIENT creados por `POST /api/users`, que no recibe `clientType`) | — | Se trata como "no empresa" |

- Lo único que tiene efecto en el backend es **si vale `company` o no** (sin distinguir
  mayúsculas). Cualquier otro valor se comporta igual que `individual`.
- Una prueba (`PersonalDosimetryApiContract`) usa `person` como valor "no empresa". No lo
  escribe ningún cliente real.
- El comentario de V10 documenta los valores previstos: `company | individual`.

---

## 5. Seguridad de la sesión

### 5.1 Contraseñas

- Se guardan con **BCrypt** (`SecurityConfig.passwordEncoder()` → `BCryptPasswordEncoder`, con
  la fuerza por defecto).
- Longitud mínima: **6** en el registro (`RegisterRequest`) y **8** al cambiarla
  (`ChangePasswordRequest`). `CreateUserRequest` (admin) solo exige que no esté vacía.

### 5.2 JWT

| Aspecto | Valor |
|---|---|
| Librería | jjwt 0.12.6 |
| Firma | HMAC-SHA con `Keys.hmacShaKeyFor(Base64.decode(jwt.secret))`. jjwt elige HS256, HS384 o HS512 según el largo de la clave. |
| Claims | `sub` = email, `role` = rol en MAYÚSCULAS, `userId` = `users.id`, `iat`, `exp` |
| Duración | `jwt.expiration=86400000` ms (**24 h**), en `application.properties` |
| Transporte | Cabecera `Authorization: Bearer <token>` (`JwtAuthFilter`). Sin cookies ni sesión de servidor (`STATELESS`, CSRF desactivado). |
| Validación | Firma y `exp` (`JwtUtil.isTokenValid`). Después se recarga la cuenta desde la base (§2.1). |
| Renovación | `POST /api/auth/refresh` (§6.1) |
| Revocación | No hay lista de revocación. Un token deja de valer solo si expira o si la cuenta queda inactiva o se borra. |

### 5.3 Secreto JWT (`jwt.secret` / `JWT_SECRET`)

Lo lee **un solo componente**, `JwtUtil` (`@Value("${jwt.secret}")`). `JwtAuthFilter`,
`AuthService`, `StompAuthChannelInterceptor` y los controladores de Client Portal y Field
Service usan `JwtUtil`, nunca la propiedad.

**Formato:** debe ser **Base64** y decodificar a **al menos 32 bytes** (256 bits, el mínimo de
HMAC-SHA256). Se genera así:

```bash
openssl rand -base64 48     # 64 caracteres, 48 bytes
```

**Dónde se define, por perfil:**

| Perfil | Valor | Archivo |
|---|---|---|
| (base) | `${JWT_SECRET:}`, **sin valor por defecto** | `application.properties` |
| `dev` (perfil por defecto) | `${JWT_SECRET:<valor de desarrollo>}`: arranca sin configurar nada | `application-dev.properties` |
| `test` | Valor fijo de pruebas | `src/test/resources/application-test.properties` (y algunos `@SpringBootTest(properties=...)`) |
| `prod`, `demo` | Solo la variable `JWT_SECRET` | — |

**Validación al arrancar (`JwtSecretValidator`):** solo existe en los perfiles **`prod` y
`demo`** (`@Profile({"prod","demo"})`; también con `prod,ingestion`). **No actúa en `dev` ni en
`test`.** Es un `BeanFactoryPostProcessor`, así que corre antes de crear cualquier bean
(DataSource, Flyway…) y su error no queda tapado por otro. Impide arrancar si `JWT_SECRET`:

1. falta o está vacío;
2. es el valor de desarrollo o el de pruebas (publicados en el repositorio);
3. tiene menos de **32 caracteres**;
4. no es Base64 válido, o decodificado mide menos de **32 bytes**.

El fallo se muestra con el recuadro estándar de Spring Boot (`JwtSecretFailureAnalyzer`,
registrado en `META-INF/spring.factories`). **El mensaje nunca incluye el secreto.** Ejemplo:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Secreto JWT inválido para los perfiles prod/demo: JWT_SECRET no está definido o está vacío.

Action:

Define la variable de entorno JWT_SECRET con un valor Base64 propio de al menos 32 caracteres
que decodifique a 32 bytes o más (por ejemplo: openssl rand -base64 48). No reutilices el
valor de application-dev.properties.
```

Si se cambia el secreto en un entorno, todos los tokens emitidos dejan de valer y los
usuarios tienen que volver a iniciar sesión.

### 5.4 Bloqueo por intentos fallidos (`LoginAttemptService`)

| Aspecto | Valor |
|---|---|
| Intentos permitidos | **5** fallos seguidos (`MAX_ATTEMPTS = 5`). El 5.º fallo activa el bloqueo. |
| Duración del bloqueo | **15 minutos** (`LOCK_DURATION_MS = 15 * 60 * 1000`) |
| Clave | Email en minúsculas (por cuenta, no por IP) |
| Qué cuenta como fallo | `BadCredentialsException` en el login: email inexistente o contraseña incorrecta. Una cuenta `pending` o `inactive` con la contraseña correcta **no** suma fallos (responde 400 con su propio mensaje). |
| Mientras está bloqueada | `LockedException` → **HTTP 429** con `"Account temporarily locked. Try again in N seconds."`. No se comprueba la contraseña ni se suman fallos. |
| Login correcto | Borra el contador de esa cuenta |
| Dónde se configura | **Constantes en el código** (`LoginAttemptService.java`). No hay propiedad en `application*.properties`. |
| Almacenamiento | `ConcurrentHashMap` **en memoria**: se pierde al reiniciar y no se comparte entre réplicas (prod corre con una sola réplica). |

Las credenciales incorrectas responden siempre **401** `"Invalid email or password"`
(`GlobalExceptionHandler`), sin distinguir si el email existe.

### 5.5 WebSocket (`/ws`)

- El handshake HTTP de `/ws/**` es `permitAll` en `SecurityConfig`. La autenticación se hace
  en el **`CONNECT` de STOMP** (`StompAuthChannelInterceptor`), que exige
  `Authorization: Bearer <jwt>` válido de una cuenta activa.
- Cada `SUBSCRIBE` solo se acepta para los tópicos propios:

| Tópico | Quién |
|---|---|
| `/topic/clients/{id}/readings`, `/topic/workers/{id}/alerts` | La cuenta con ese `id` |
| `/topic/organizations/{id}/alerts` | El `SSO_RESPONSIBLE` de esa organización |
| `/topic/readings`, `/topic/discovery` | `TECHNICIAN` |
| Cualquiera | `ADMIN` |

- `WebSocketConfig` admite cualquier origen (`setAllowedOriginPatterns("*")`), con y sin
  SockJS.

---

## 6. Endpoints de IAM

El prefijo real es **`/api/auth`, `/api/users` y `/api/sso`**, **no `/api/v1/...`** como dice
el informe. Todas las respuestas van envueltas en `ApiResponse`
(`{ success, message, data, timestamp }`).

### 6.1 `/api/auth` (público, `AuthController`)

| Método y ruta | Cuerpo | Respuesta | Notas |
|---|---|---|---|
| `POST /api/auth/login` | `{ email, password }` | `LoginResponse { token, email, name, initials, role, userId, technicianId, organizationId }` | `role` en minúsculas. `technicianId` = `userId` solo si es `TECHNICIAN`. Errores: 401 (credenciales), 429 (bloqueo), 400 (cuenta `pending` o `inactive`). Guarda `lastLogin`. |
| `POST /api/auth/refresh` | — (cabecera `Authorization: Bearer <token vigente>`) | `RefreshResponse { token, expiresIn }` | El token **todavía no debe haber expirado**. Relee la cuenta: si no existe o no está activa → 401. El token nuevo lleva el rol actual de la base. |
| `POST /api/auth/register` | `{ name, email, password, phone?, address?, clientType?, contactName?, taxId?, industry? }` | `data: null`, mensaje de "pendiente de activación" | Alta desde la app móvil: crea un **`CLIENT` en estado `pending`**. No puede iniciar sesión hasta que un admin la active (`PUT /api/users/{id}` con `status: "active"`). Email repetido → 400. |

### 6.2 `/api/users` (`UserController`)

| Método y ruta | Quién | Qué hace |
|---|---|---|
| `GET /api/users?role=` | `ADMIN` | Lista usuarios, filtrando opcionalmente por rol (sin distinguir mayúsculas; un rol inválido da 400). |
| `GET /api/users/{id}` | `ADMIN` | Un usuario. |
| `POST /api/users` | `ADMIN` | Crea una cuenta **activa**. `CreateUserRequest`: `name`, `email`, `password`, `role` (obligatorios), `organizationId` (obligatorio para `WORKER` y `SSO_RESPONSIBLE`), opcionales de perfil. Devuelve 201. |
| `PUT /api/users/{id}` | `ADMIN`; `TECHNICIAN`, `WORKER` y `SSO_RESPONSIBLE` solo sobre su propia cuenta (`assertSelfOrAdmin`) | Actualización parcial (`UpdateUserRequest`): perfil, `status`, campos de cliente y `password`. |
| `PATCH /api/users/{id}/password` | Igual que `PUT` | `{ currentPassword, newPassword }`. Verifica la actual. La nueva debe tener 8 caracteres o más. |
| `DELETE /api/users/{id}` | `ADMIN` | Borra la cuenta. 400 si es una organización con cuentas ligadas. |
| `GET /api/users/profile` | `TECHNICIAN`, `ADMIN`, `WORKER`, `SSO_RESPONSIBLE` | La cuenta del token. **`CLIENT` no está en la regla** (cae en `anyRequest` → 403); el cliente usa `GET /api/client/profile`. |

`UserDto` expone `role` en title case (§2) y **nunca** el hash de la contraseña.

> **Observación (comportamiento actual, sin cambios):** `PUT /api/users/{id}` acepta los
> campos `password` y `status` (`UserService.update`). Un `TECHNICIAN`, `WORKER` o
> `SSO_RESPONSIBLE` puede, sobre su propia cuenta, cambiar la contraseña **sin enviar la
> actual** (a diferencia de `PATCH .../password`) y cambiar su propio `status`. El `role` y
> el `organizationId` no se pueden modificar por esta vía.

### 6.3 `/api/sso` (`SsoController`, solo `SSO_RESPONSIBLE`)

| Método y ruta | Qué hace |
|---|---|
| `GET /api/sso/workers` | Los `WORKER` de la organización del responsable autenticado. |
| `GET /api/sso/workers/{id}` | Un operario. 404 si no existe; 403 si no es `WORKER` o es de otra organización. |

Un SSO sin `organizationId` recibe 403. El `ADMIN` **no** tiene acceso a `/api/sso/**`.

### 6.4 Cuenta del cliente (fuera de IAM, en `/api/client`)

El `CLIENT` gestiona su cuenta desde Client Portal (`clientportal/interfaces/rest/ClientController`):
`GET` y `PUT /api/client/profile`, `PATCH /api/client/password` y
`POST /api/client/account/delete` (baja de la propia cuenta, pide la contraseña).

---

## 7. Reglas de seguridad vigentes (`SecurityConfig`)

Spring Security evalúa las reglas **en orden y se queda con la primera que coincide**.

| # | Ruta | Método | Acceso |
|---|---|---|---|
| 1 | `/api/auth/login`, `/api/auth/refresh`, `/api/auth/register` | POST | **Público** |
| 2 | `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**` | todos | **Público** |
| 3 | `/api/v1/ai-forecasting/**` | todos | Autenticado |
| 4 | `/api/v1/personal-dosimetry/**` | todos | Autenticado (sin token → **401**, no 403) |
| 5 | `/api/v1/occupational-health/**` | todos | `SSO_RESPONSIBLE`, `WORKER`, `ADMIN` |
| 6 | `/api/v1/plant-config/**` | todos | `SSO_RESPONSIBLE`, `WORKER`, `ADMIN` |
| 7 | `/api/v1/device-management/**` | todos | Autenticado (el caso de uso decide los permisos) |
| 8 | **`/api/v1/**` (resto)** | todos | **Público** ⚠️ |
| 9 | `/ws/**` | todos | Público en HTTP; JWT en el `CONNECT` de STOMP (§5.5) |
| 10 | `/api/tech/**` | todos | `TECHNICIAN`, `ADMIN` |
| 11 | `/api/client/**` | todos | `CLIENT`, `ADMIN` |
| 12 | `/api/sso/**` | todos | `SSO_RESPONSIBLE` |
| 13 | `/api/alarms/*/acknowledge` | POST | `WORKER`, `ADMIN` |
| 14 | `/api/alarms/*/escalate` | POST | `SSO_RESPONSIBLE`, `ADMIN` |
| 15 | `/api/alarms/mine` | GET | `WORKER` |
| 16 | `/api/alarms/organization` | GET | `SSO_RESPONSIBLE` |
| 17 | `/api/users/profile` | GET | `TECHNICIAN`, `ADMIN`, `WORKER`, `SSO_RESPONSIBLE` |
| 18 | `/api/users/*` | PUT | `TECHNICIAN`, `ADMIN`, `WORKER`, `SSO_RESPONSIBLE` (+ `assertSelfOrAdmin`) |
| 19 | `/api/users/*/password` | PATCH | `TECHNICIAN`, `ADMIN`, `WORKER`, `SSO_RESPONSIBLE` (+ `assertSelfOrAdmin`) |
| 20 | Cualquier otra | todos | `ADMIN` |

Otros detalles:

- **Respuesta sin autenticación:** 403 en general; **401** solo bajo
  `/api/v1/personal-dosimetry/**`.
- **Riesgo abierto (regla 8):** todo lo que esté bajo `/api/v1/**` sin regla propia es
  público, y cualquier controlador nuevo con ese prefijo nace público. Hoy cae ahí
  `ReadingController` (`/api/v1/readings`). Está pendiente reemplazarlo por `authenticated()`
  más una lista blanca explícita.
- **Filtros:** `JwtAuthFilter` va antes de `UsernamePasswordAuthenticationFilter`, y
  `DosimetryAccessAuditFilter` (Personal Dosimetry) va después de él.
- `@EnableMethodSecurity` está activo, pero los controladores de IAM no usan `@PreAuthorize`:
  el control fino está en `assertSelfOrAdmin` y `SsoService`.
- **CORS** (`CorsConfig`): orígenes de `cors.allowed-origins` (`CORS_ALLOWED_ORIGINS`, por
  defecto `http://localhost:4200`); cabeceras `Authorization`, `Content-Type`, `Accept`,
  `ngrok-skip-browser-warning`.

---

## 8. Variables de entorno de seguridad

| Variable | Perfiles | Obligatoria | Si falta |
|---|---|---|---|
| `JWT_SECRET` | `prod`, `demo` | **Sí** | **No arranca** (`JwtSecretValidator`, §5.3). Tampoco arranca si es el valor de desarrollo o de pruebas, si tiene menos de 32 caracteres o si no es Base64 de 32 bytes o más. |
| `JWT_SECRET` | `dev` | No | Usa el valor de desarrollo de `application-dev.properties`. |
| `JWT_SECRET` | `test` | No | Usa el valor de `application-test.properties`. |
| `SPRING_PROFILES_ACTIVE` | todos | No | `dev` (`application.properties`). El `Dockerfile` fija `prod`. |
| `CORS_ALLOWED_ORIGINS` | todos | En la práctica, sí en `prod` y `demo` (el front está en otro dominio) | `http://localhost:4200`: el front desplegado no puede llamar a la API. |

Las variables de base de datos (`MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD`) también son
obligatorias en `prod` y `demo`, pero no son de IAM.

**No hay bootstrap de administrador por variables de entorno.** `DataInitializer` (cuentas
de demostración con contraseñas fijas) solo corre en `dev`, `demo` y `test`
(`@Profile({"dev","demo","test"})`): en `prod`, una base vacía arranca **sin ningún
usuario**. Cómo se crea el primer ADMIN en prod no está resuelto en el código.

---

## 9. Lo que NO existe todavía

Para no prometerlo en el informe:

| Funcionalidad | Estado en el código |
|---|---|
| Verificación de correo (`/auth/email-verifications`) | No existe. El alta móvil queda `pending` hasta que un admin la activa a mano. |
| Recuperación de contraseña (`/auth/password-reset-requests`, `/auth/password-resets`) | No existe. Solo se puede cambiar estando autenticado, o la cambia un admin. |
| Logout en el servidor (`/auth/logout`) | No existe. El "logout" de la app y del portal solo borra la sesión local; el token sigue valiendo hasta su `exp` (24 h). No hay lista de revocación. |
| Rol `SUPER_ADMIN` | No existe en `Role.java`. |
| Envío de correo | No hay adaptador SMTP ni `spring-boot-starter-mail`. |
| Auditoría de identidad (`IdentityAuditPort`) | No existe. Solo Personal Dosimetry audita sus accesos (`DosimetryAccessAuditFilter`). |
| Agregado `Organization` | No existe: la organización es una cuenta `CLIENT` (§3). |
| Identificadores UUID | No: todos son `Long`. |
| Configuración del bloqueo por propiedades | No: 5 intentos y 15 minutos son constantes en el código. |
| Bootstrap del primer ADMIN en prod | No (§8). |
