# yaver-ng-client — Angular 22 OpenAPI Generator

`yaver-ng-client` is the final, opinionated Angular generator of the Yaver OAS
Generator suite. It produces **one production artifact** for an API: a
buildable [Angular Package Format](https://angular.dev/tools/libraries/angular-package-format)
library for **Angular 22.1+** that is **zoneless by construction**.

It is a breaking redesign of `yaver-ts-angular`. There is no compatibility
mode, no legacy entrypoint and no v1/v2 switch.

## Why this design

| Decision | Rationale |
| --- | --- |
| Queries use `httpResource` | Reads are reactive state. `httpResource` gives `value/hasValue/status/error/isLoading/reload`, request cancellation on parameter change, and injection-context lifecycle — without a single generated subscription, effect or hand-written signal proxy. |
| Mutations stay `Observable<T>` | Commands are not state; they are one-shot events. RxJS keeps cancellation, error flow, retries and interceptor semantics. Wrapping every POST in a signal resource would invent shared mutable state where none belongs. |
| Promise conversion happens only at imperative boundaries | `await firstValueFrom(api.createTenant(...))` is a UI-event concern (e.g. a Signal Forms submission action). The generator does not force Promise semantics onto all consumers. |
| No global caching | `httpResource` resources are created per injection site and destroyed with their injector. Cross-component caching, optimistic updates and deduplication are application/store concerns (see below). |
| No `provideHttpClient()` | The application owns the HTTP stack: backends (`withFetch()` etc.), interceptors, transfer cache, XSRF, retries, telemetry. The generated client only consumes its own configuration. |
| One APF artifact | Monorepo and npm consumers import the same package path from the same built `dist`. Copying generated sources into an app is forbidden — it bypasses partial compilation, drifts from the contract and breaks upgrades. |

## Installation / CLI

```bash
java -cp cli/openapi-generator-cli.jar:cli/yaver-generator-cli.jar \
  org.openapitools.codegen.OpenAPIGenerator generate \
  -g yaver-ng-client \
  -i spec.yaml \
  -o out/client \
  --additional-properties=npmName=@yaver/admin-api \
  --additional-properties=npmVersion=1.0.0 \
  --additional-properties=clientPrefix=PairsAdmin
```

Build and pack the generated library:

```bash
cd out/client
npm install
npm run build     # ng-packagr -> dist/ (APF, partial compilation)
npm run pack      # builds and runs `npm pack ./dist`
```

## Generator options

| Option | Default | Description |
| --- | --- | --- |
| `npmName` | — | Package name. Required to generate the full buildable library. |
| `npmVersion` | spec version | Package version. |
| `ngVersion` | `22.1.3` | Angular target. Only `>= 22.1 < 23` is accepted; the generated peer range is `^22.1.0`. |
| `clientPrefix` | `Yaver` | Prefix for configuration symbols: `<Prefix>ClientConfig`, `<PREFIX>_CLIENT_CONFIG`, `provide<Prefix>Client`. |
| `fileNaming` | `camelCase` | `camelCase` or `kebab-case` for generated file names. |
| `stringEnums` | `false` | Emit TypeScript enums instead of const objects for OpenAPI enums. |
| `taggedUnions` | `false` | Discriminator-based tagged unions instead of inheritance. |
| `enumNameSuffix`, `enumPropertyNaming`, `modelPropertyNaming`, `paramNaming`, `licenseName` | — | Standard OpenAPI Generator naming options. |

Deliberately **removed** options (vs `yaver-ts-angular`): `providedIn`,
`apiModulePrefix`, `configurationPrefix`, `serviceSuffix`, `withInterfaces`,
`useSingleRequestParameter`, `queryParamObjectFormat`, `zonejsVersion`,
`rxjsVersion`, `ngPackagrVersion`, `httpContextInOptions`, and everything
related to the Angular 9–21 module era. This generator is opinionated; the
help output only lists options that actually do something.

## Package layout

```
<package-name>            root entrypoint
  ├── src/configuration.ts      config interface, injection token, provide<Prefix>Client
  ├── src/api/<tag>.api.ts      params interfaces, command API classes, resource initializers
  ├── src/models/               request/response models
  └── src/internal/             request builders, param serialization (not exported)
<package-name>/forms      Signal Forms schemas + x-yaver-form metadata
<package-name>/testing    yaverTestClientConfig helper (no production side effects)
```

The generated `package.json` declares:

- `sideEffects: false`
- peers: `@angular/common`, `@angular/core`, `@angular/forms` (optional),
  `rxjs` (`^22.1.0` / `^7.8.0`)
- runtime dependency: `tslib` only
- no `zone.js`, no `ng-packagr` at runtime

Build dependencies are pinned from the internal Angular 22.1 matrix
(`angularDependenciesByVersion.yaml`): TypeScript `~6.0.3`, ng-packagr
`22.1.1`. Partial-Ivy compilation is configured in `tsconfig.json`
(`compilationMode: "partial"`).

## Operation classification

Classification is computed in Java; templates only render flags.

| Operation | Generated API |
| --- | --- |
| GET/HEAD whose selected Accept header is JSON (or absent) | `httpResource` query |
| GET/HEAD responding `text/event-stream` | Observable stream API (`xxxEvents`, `text` + progress) |
| GET/HEAD returning binary/file (`application/pdf`, `application/octet-stream`, …) | Observable download command (`Observable<Blob>`) |
| GET/HEAD returning text | Observable command (`Observable<string>`) |
| POST/PUT/PATCH/DELETE (any response) | Observable command |
| multipart upload (`multipart/form-data` with file) | Observable command + events variant |
| DELETE/POST with 204/no body | Observable command returning `Observable<void>` |

The **selected Accept header** is computed with the same rule the client uses
at runtime (first JSON mime, else first entry), so classification can never
diverge from the wire format. Binary downloads can therefore never silently
become JSON resources.

Generated names per operation:

- command method: `camelize(operationId)` → `createTenant`
- params interface: `CreateTenantParams` — the request body is always the
  `body` property: `api.createTenant({ body: request })`
- resource initializer: `injectCreateTenantResource(...)`
- resource options: `CreateTenantResourceOptions` (`debugName`)
- response / event variants: `createTenantResponse(...)` →
  `Observable<HttpResponse<T>>`, `createTenantEvents(...)` →
  `Observable<HttpEvent<T>>`. Variants exist only where meaningful:
  - `Response`: all commands and streams
  - `Events`: uploads, downloads and streams (progress reporting)

Angular 22.1 note: `HttpClient.request` overloads for `blob`/`text` are
non-generic and already precise (`Observable<Blob>`, `Observable<string>`),
so the generator emits no type argument for them; JSON calls are typed
generically.

### `x-yaver-ng-mode`

```yaml
paths:
  /tenants/{id}/logo:
    put:
      x-yaver-ng-mode: command   # query | command | stream
```

- `command` — documented escape hatch: forces the imperative Observable API
  (e.g. for reads with side effects or metering).
- `query` — only allowed for safe reads: GET/HEAD, JSON response, no request
  body, no binary, no stream. Anything else fails generation.
- `stream` — only allowed when the operation responds with
  `text/event-stream`. Anything else fails generation.
- Unknown values fail generation with the operation id in the message.

## Client configuration and providers

```ts
import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { PairsAdminClientConfig, providePairsAdminClient } from '@yaver/pairs-admin';

bootstrapApplication(AppComponent, {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(), // the app chooses backends/interceptors
    providePairsAdminClient({
      basePath: environment.adminApiUrl, // required, validated at bootstrap
      withCredentials: false,
    } satisfies PairsAdminClientConfig),
  ],
});
```

- `basePath` is required and validated when the provider runs (empty/missing
  values throw immediately).
- The generated client never calls `provideHttpClient`, never imports
  `NgZone`, never touches `zone.js`, never subscribes, and never accesses
  browser globals at module scope (SSR-safe).
- Requests flow through the application's interceptor chain in both transport
  paths (`httpResource` is backed by `HttpClient`).

## Queries (`httpResource`)

```ts
@Component({ /* ... */ })
export class TenantListComponent {
  private readonly tenantId = signal('tenant-7');

  // Reactive query: re-runs when the factory output changes.
  readonly tenant = injectGetTenantResource(() => ({
    tenantId: this.tenantId(),
  }));

  // Conditional query: returning undefined keeps the resource idle.
  readonly detail = injectListTenantContactsResource(() => {
    const tenantId = this.tenantId();
    return tenantId ? { tenantId } : undefined;
  });
}
```

Semantics:

- `assertInInjectionContext` guards the call; resources must be created in
  field initializers / constructors / factories.
- Returns an accurate `HttpResourceRef<T | undefined>`; operations without a
  declared success body produce `HttpResourceRef<unknown>`.
- `undefined` from the params factory = idle (no request). Optional and
  nullable parameters are preserved as distinct TypeScript shapes.
- Required parameters are validated at request build time; a missing value
  puts the resource into the `error` state with
  `Required parameter <name> was null or undefined when calling <op>.` and
  no request is sent.
- No debouncing, no global cache, no cross-instance sharing, no effects
  copying data into other signals.
- Consumers use the real Angular API: `value()`, `hasValue()`, `status()`,
  `error()`, `isLoading()`, `reload()`, plus `headers`/`statusCode` on the
  ref.

## Mutations (Observable commands)

```ts
private readonly tenantsApi = inject(TenantsApi);

save(request: CreateTenantRequest): Observable<TenantDetail> {
  return this.tenantsApi.createTenant({ body: request });
}

// imperative boundary (e.g. Signal Forms submission):
await firstValueFrom(this.tenantsApi.createTenant({ body: request }));
```

- `@Injectable({ providedIn: 'root' })` classes grouped by OpenAPI tag
  (`TenantsApi`, `ReportsApi`, …), created with `inject(HttpClient)`.
- Precise return types — no `Observable<any>`, no untyped options objects,
  no overload sets backed by `observe: any`.
- Errors flow through the Observable (`HttpErrorResponse`); cancellation via
  unsubscribe aborts the request; interceptors see every request.
- `void` commands (204) return `Observable<void>`.

## Shared request construction

Each operation has exactly one generated internal builder
(`buildCreateTenantRequest(...)`) producing an `InternalRequest` descriptor
(method, path, query, headers, body, response type). Both the command API and
the resource initializer consume the same builder, so URL construction,
OpenAPI serialization (repeated query keys, `style`/`explode`, deep objects,
date/date-time, enums, form-URL-encoding, multipart), Accept/Content-Type
selection, path escaping and credentials behave identically in both paths.
The descriptor and helpers live in `src/internal/` and are not exported from
the package.

Wire compatibility with `yaver-ts-angular` is verified per fixture by
`sample/ng-client-wire-check.py` (methods + URL templates + query keys + form
keys must match exactly).

## Signal Forms (`<package>/forms`)

```ts
import { form, submit } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { createTenantRequestSchema } from '@yaver/pairs-admin/forms';

readonly model = signal<CreateTenantRequest>({
  name: 'Acme Inc.', plan: 'pro', adminEmail: 'admin@acme.test',
});

readonly tenantForm = form(this.model, createTenantRequestSchema, {
  submission: {
    action: async (field) => {
      await firstValueFrom(this.tenantsApi.createTenant({ body: field().value() }));
      return undefined;
    },
  },
});
```

Constraint mapping (all `@publicApi 22.0` from `@angular/forms/signals`):

| OpenAPI | Signal Forms |
| --- | --- |
| `required` (and **not** nullable) | `required(p.field)` |
| `minLength` / `maxLength` | `minLength(p.field, n)` / `maxLength(p.field, n)` |
| `pattern` | `pattern(p.field, new RegExp('…'))` |
| `minimum` / `maximum` (inclusive) | `min(p.field, n)` / `max(p.field, n)` |
| `format: email` | `email(p.field)` |

Rules:

- `required` is **never** emitted for nullable properties (null is a valid
  value) and never for optional properties (optional is not converted into
  required).
- Properties that may be `undefined`/`null` get their constraints inside
  `applyWhenValue(p.field!, (v): v is NonNullable<…> => v != null, (v) => { … })`
  so empty values do not fail constraint validation. The `!` only strips the
  type-level `undefined` branch of the *field path* union; the path object
  always exists.
- Nested object models use `apply(p.field, nestedSchema)`; arrays use
  `applyEach(p.field, itemSchema)` or inline per-item validators.
- No default form values are invented.
- Schemas are generated for every request-body model (transitively including
  nested models) in the `/forms` secondary entrypoint; the root entrypoint
  never imports `@angular/forms`.

### `x-yaver-form` (explicit UI metadata only)

```yaml
CreateTenantRequest:
  properties:
    plan:
      type: string
      enum: [starter, pro, enterprise]
      x-yaver-form:
        label: Plan
        control: select
        options:
          - { value: starter, label: Starter }
          - { value: pro, label: Pro }
```

Supported keys: `label`, `placeholder`, `control`
(`text | textarea | number | email | password | checkbox | select | date |
hidden`), `options: [{ value, label }]`. Unknown keys or control kinds fail
generation. Metadata is emitted as typed constants
(`createTenantRequestFormMeta: Readonly<Partial<Record<keyof CreateTenantRequest,
YaverFormFieldMeta>>>`) in the `/forms` entrypoint. There is no untyped
`YaverUiModels` structure, and nothing is ever inferred from plain OpenAPI
fields.

## File upload / download / streams

| Operation | Methods |
| --- | --- |
| multipart upload | `uploadX(params)`, `uploadXResponse(params)`, `uploadXEvents(params)` (progress) |
| binary download | `downloadX(params)` → `Observable<Blob>`, `+Response`, `+Events` |
| `text/event-stream` | `streamXEvents(params)` → `Observable<HttpEvent<string>>` (partial text chunks), `+Response` |

For multipart bodies the client deliberately does **not** set a manual
`Content-Type` header, so the browser generates the boundary. Progress
streams require the XHR backend (`withXhr()` in the app's
`provideHttpClient`) — the Fetch backend does not report upload progress.

## Error handling

4xx/5xx responses are not modeled as separate return types; they surface
through the transport error channel (`HttpErrorResponse` on the Observable
error path, `resource.error()` for queries). Generated JSDoc lists the
declared error status codes per operation. `application/problem+json`
payloads can be read from `HttpErrorResponse.error`.

## Local monorepo consumption

One artifact, one import path. From the built `dist`:

```jsonc
// consumer package.json
"dependencies": { "@yaver/admin-api": "file:../admin-api/dist" }
```

or an npm/yarn/pnpm workspace dependency pointing at the library project, or
a TypeScript path mapping to the **built package**:

```jsonc
// tsconfig paths -> built APF output, never to src/public-api.ts
"@yaver/admin-api": ["../admin-api/dist"]
```

Rules enforced by tests and gates:

- never map imports to `src/public-api.ts` or raw generated sources;
- never copy generated `src` into an application;
- never rewrite `main`/`module`/`types` to source files;
- never swallow build failures (`tsc … || true` is banned).

Note: a `file:` dependency is symlinked by npm. The package directory must be
resolvable from the consumer's node_modules lookup path (inside the monorepo
tree with hoisted dependencies) — exactly like a published package.

## npm consumption

```bash
cd admin-api && npm run pack   # -> dist build + yaver-admin-api-<v>.tgz
npm install ./yaver-admin-api-1.0.0.tgz   # in any Angular 22.1 app
# or publish the very same tarball in CI
```

The consumer import path is identical to local mode: `@yaver/admin-api`,
`@yaver/admin-api/forms`, `@yaver/admin-api/testing`.

## Testing generated clients

```ts
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideYaverClient } from '@yaver/admin-api';
import { yaverTestClientConfig } from '@yaver/admin-api/testing';

TestBed.configureTestingModule({
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(),
    provideHttpClientTesting(),
    provideYaverClient(yaverTestClientConfig('http://test.api')),
  ],
});
```

`HttpTestingController` works for both transport paths (queries and
commands). The consumer fixture in `sample/ng-client-consumer` demonstrates
query/request/cancel/reload/error flows, `firstValueFrom` mutations,
interceptor participation, multipart upload, download, stream events and
Signal Forms submission — in a fully zoneless test bed.

## Repository test pipeline

```bash
./build.sh                     # generator jar
cd yaver-codegen && mvn test   # generator unit tests
sample/test-ng-client.sh           # generate -> ng-packagr build -> quality gates
                               # -> npm pack -> determinism -> wire check
                               # -> zoneless consumer tests (local + tarball)
sample/ng-client-quality-gate.py <dist>   # static gates over the built artifact
```

Quality gates fail on: `@ts-ignore`, `: any`, `<any>`, `any[]`,
`Observable<any>`, `observe: any`, `as any`, `zone.js` references, `NgZone`,
generated `provideHttpClient` calls, generated `.subscribe()` in
declarations, source-path exports, missing secondary entrypoints, and any
`/legacy`, `/v1`, `/rxjs-compat` directory.

## Known limitations

- Streams are raw chunk text (`HttpEvent<string>` partial text); SSE frame
  parsing (event/id/retry) is intentionally left to the consumer. `httpResource`
  is never used for streams.
- Upload/download **progress** events require the XHR backend; with the Fetch
  backend only the response event is delivered.
- Inline (anonymous) request-body schemas are not eligible for form schema
  generation — give the schema a component name to opt in.
- Exclusive bounds (`exclusiveMinimum/Maximum`), `multipleOf`, `minItems`/
  `maxItems` (on arrays as *items* constraints they do map) and async
  validation are not mapped; unmapped semantics are skipped, never guessed.
- Nullable items inside primitive arrays do not get per-item validators (no
  typed Signal Forms validator accepts `T | null` paths).
- Query resources do not automatically debounce; compose signals upstream if
  needed.
- The generator does not emit app-level stores/facades/caching — by design.
  Application services remain appropriate for orchestration, shared feature
  state, optimistic updates, permission logic and cross-component caching;
  they are **not** needed for plain call / loading-state / result-signal
  plumbing.
