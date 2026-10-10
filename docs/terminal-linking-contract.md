# Terminal linking contract

Implementation coordination for `codex/vinculacion-backend-app-venta`.

## Confirmed behavior

- License Windows capacity N exposes codes 001..N; 001 belongs to the backend PC and consumes one slot for VENTA and GESTIÓN together. Other available codes are selectable in any order.
- A logical terminal retains its UUID and economic history. Each physical binding has a fresh UUID and credential. Release revokes the binding and its sessions, retains the logical terminal/cash history, and frees its code.
- One Windows profile per physical PC. Classic current VENTA UI and existing GESTIÓN Security > Terminales y PDA, ES/EN/ZH.
- First install wizard; permanent Configure connection action on login; session settings entry. Manual host/port and DNS-SD discovery. HTTPS and normal certificate verification for remote connections.
- Changing address of the same authenticated installation preserves identity and pending data. A different installation is never silently adopted.

## Backend API (all paths below /api/v1)

- GET `/terminal-linking/bootstrap?challenge=<base64url random nonce>` returns `{protocolVersion:1,installationId,installationReference,storeId,storeName,publicKey,challenge,signature,maxWindows,slots}`. Public key is base64 DER SPKI. Signature is SHA256withRSA over UTF-8 `TPV-TERMINAL-LINKING-V1\n<challenge>\n<installationId>`. Signing identity must agree with the installation public key. Slots are minimal `{code,status,name?,terminalId?,bindingId?,expiresAt?,outOfQuota?}`.
- POST `/terminal-linking/requests`: `{requestId,deviceId,credential,code,name,deviceName}`. Client persists cryptographically random credential and requestId BEFORE submission. Idempotency is scoped by requestId AND proof of credential; retries cannot retrieve another request's secret. Returns LinkState.
- POST `/terminal-linking/requests/status` and `/terminal-linking/requests/cancel`: `{requestId,credential}` -> LinkState. Status is a limited proof-authenticated operation without cashier login. No credentials in URLs.
- LinkState: `{requestId,bindingId,terminalId,terminalCode,terminalName,storeId,storeName,installationId,status,expiresAt?}`. Status is PENDING, ACTIVE, DISABLED, RELEASED, CANCELLED or EXPIRED. No credential echo needed.
- GET `/terminals/workstations`: authenticated management `{maxWindows,slots,legacyTerminals}`. Management slot also includes `deviceName,lastSeenAt,terminalId,bindingId,expiresAt,outOfQuota` when present. Legacy terminals are Windows records awaiting explicit code assignment, with `{id,name,type,approved,active}`.
- POST `/terminals/workstations/{code}/approve`, `/deactivate`, `/release`, `/cancel`: `{bindingId}`. Expected binding prevents stale UI actions affecting a replacement. Response may be the refreshed management view.
- GET `/terminals/workstations/{code}/history`: array `{bindingId,requestId?,deviceName,name,status,createdAt,approvedAt?,endedAt?}`.
- POST `/terminals/workstations/legacy/{terminalId}/assign-code`: `{code}`. Preserve terminal UUID, credentials and historical references; reject collision/over-quota. Existing PDA endpoints remain compatible.

Code 001 provisioning/adoption uses existing protected installation-admin authorization or verified existing local identity, never an anonymous remote claim:

- POST `/terminal-linking/server/adopt`: `{requestId,deviceId,credential,deviceName,terminalId,name?}`. Requires loopback and proof of the existing server credential; no rotation.
- POST `/terminals/workstations/server/adopt`: same body without `terminalId`, authorized by protected installation ADMIN and loopback. Explicit initial/replacement provisioning, not automatic startup rotation.
- POST `/terminal-linking/legacy/adopt`: same body with `terminalId`, proof of an existing Windows credential after management assigned a code. One-time adoption marker prevents reusing this route to take over or revive a released binding.

## Desktop bridge proposal

`window.tpvDesktop.backendConnection` methods return `{ok:true,...}` or `{ok:false,code,message}`. Secrets remain in the main process until the existing approved identity is loaded through terminalIdentity.

- `load()` -> `{configuration?:{backendUrl,installationId?},link?:LinkState,identity?:TerminalContext,deviceName,configurationError?}`
- `discover()` -> `{servers:[{backendUrl,label}]}`; bounded search, includes same-computer candidate; untrusted discovery only.
- `probe({backendUrl})` -> `{server: Bootstrap, sameInstallation:boolean, localServer:boolean}`; TLS and challenge signature verified in main; pinned installation identity checked before existing credentials are sent.
- `requestLink({backendUrl,code,name,administrator?:{username,password}})` -> `{link:LinkState,identity?,restartRequired}`; durable encrypted pending state before HTTP; tested target committed using protected Windows mechanism. Administrator credentials only for explicit local 001 adoption; not persisted.
- `refreshLink()` -> `{link:LinkState,identity?:TerminalContext}`; persists completed identity on ACTIVE.
- `cancelLink()` -> `{link:LinkState}`.
- `saveAddress({backendUrl})` -> `{restartRequired:boolean}`; same authenticated installation only; commits atomically with authorized helper and preserves identity.
- `restart()` -> result; explicit safe app restart after success.

Renderer TerminalContext adds optional `installationId`, `storeId`, `bindingId`, `terminalName`, `legacyTerminalCode` and `legacyBackendScope` for safe state migration. Legacy markers are created only after successful proof of the old credential. Settings callers enforce CONFIGURACION_TERMINAL; first-login recovery is locally authorized by Windows. Main process validates IPC sender and all inputs. Activation requires restart whenever the runtime backend URL or binding changes.

## SaaS store lookup code

`storeInternalCode` is optional presentation data, separate from the store UUID, three-digit fiscal store code and terminal code. It is the permanent seven-digit `saas_store.internal_code`, with prefix 01..52 and a nonzero five-digit suffix. Local migration V271 adds nullable `tienda.saas_internal_code`; no code is generated or backfilled for unlinked stores.

New local clients request this field from SaaS `/license/link` and `/license/validate` with `X-TPV-Store-Presentation: 1`. SaaS omits the field without that exact header so older strict JSON clients continue to work. The local backend remembers the first valid code only after the response matches the linked company, store and license. A missing, invalid or conflicting value does not erase the code or prevent an authoritative license status update. The license HMAC format remains unchanged.

Bootstrap, LinkState and provisioning responses carry the optional code. Desktop caches it only after an active, verified binding; its offline display cache grants no authorization. The bootstrap signature continues to cover the challenge and installation UUID, not presentation fields. VENTAS and GESTIÓN display the code next to the store name in their footers, including login and the independent sales-document window. If missing, the desktop rechecks its verified identity every 60 seconds until the code arrives, rejecting responses for another binding and retaining all other context fields.

## Work ownership

- Backend owner: backend Java, migration, backend tests, backend security and schema release metadata. No desktop/frontend changes.
- Desktop owner: frontend/desktop CJS and tests, Windows helper under tools, packaging inclusion if required. No TS/TSX changes.
- UI owner: VENTA/GESTIÓN/common TS/TSX/CSS + localization + UI tests, including bridge types. No desktop CJS, Java or cash recovery files.
- Root: integration, secure discovery backend service, installation documentation, cash/outbox identity migration, final validation/review. Coordinate changes to shared entrypoints.
