# Angular 8 → 21 Upgrade Plan

Angular requires upgrading **one major version at a time** — each version's `ng update` schematics transform your code to be compatible with the next. With 13 major versions to traverse, this is a multi-phase effort.

---

## Phase 0 — Pre-migration prep

Fix everything that will block or complicate the version-by-version migration. Do this before touching the Angular version.

### Replace abandoned libraries

None of these have Angular 9+ support:

| Library                    | Replacement                                        |
|----------------------------|----------------------------------------------------|
| `@teamhive/ngx-tooltip`    | Angular Material `MatTooltip`                      |
| `ngx-chips` (tag inputs)   | Angular Material `MatChipGrid` + `MatAutocomplete` |
| `ngx-moment` + `moment.js` | Native `Intl.DateTimeFormat` or `date-fns`         |

### Replace deprecated tooling

- TSLint → ESLint: `ng add @angular-eslint/schematics`
- Protractor → Playwright: `ng add @playwright/test`

### Fix internal RxJS imports

5 files (`source.service.ts`, `table-graph.component.ts`, `table-graph-loader.component.ts`, `analyzed-sql.service.ts`, `tag-view.component.ts`) import from `rxjs/internal/Observable`, which breaks in RxJS 7:

```typescript
// Before
import {Observable} from 'rxjs/internal/Observable';
// After
import {Observable} from 'rxjs';
```

### Plan for ngx-graph

`@swimlane/ngx-graph` v7.x only supports Angular 8–12. Plan to upgrade to v8+ (supports Angular 14+) during Phase 1. `table-graph.component.ts` will need API updates at that point.

### Baseline

Ensure all unit tests pass before starting version upgrades. Create a git branch per major version; only merge when tests pass.

---

## Phase 1 — Angular 8 → 12 (Ivy migration)

Run these sequentially. At each step:
```bash
ng update @angular/core@N @angular/cli@N @angular/material@N
```
Fix compiler errors, run tests, commit.

### 8 → 9 (largest single step)

Ivy becomes the opt-in default compiler.

```bash
ng update @angular/core@9 @angular/cli@9
```

- The schematic migrates `static` flag on `@ViewChild` — already correctly set as `{static: true}`
- TypeScript bumps to 3.7
- `AnalyzedSqlService` `@Injectable()` may be updated by schematic to specify `providedIn`

### 9 → 10

Minor cleanup. TypeScript 3.9.

### 10 → 11

TypeScript 4.0. Stricter template type checking turns on — expect template type errors to surface that were previously silent.

### 11 → 12

TypeScript 4.2. Ivy is now the only compiler; View Engine is gone in the next step.

- Upgrade `@swimlane/ngx-graph` to v8 at this point before continuing to Phase 2
- `karma-coverage-istanbul-reporter` → `karma-coverage`

---

## Phase 2 — Angular 12 → 15

### 12 → 13

View Engine removed entirely. All libraries must be Ivy-compiled — any remaining non-Ivy-compatible libraries will fail here.

- TypeScript 4.4

### 13 → 14

Standalone components introduced (optional to adopt yet).

- TypeScript 4.6
- **Upgrade RxJS 6 → 7 here:**
  ```bash
  ng update rxjs
  ```
  The 3-argument `.subscribe(next, error, complete)` pattern is deprecated in RxJS 7. Replace across the codebase with the observer object form:
  ```typescript
  // Before
  observable.subscribe(data => {}, err => {}, () => {});
  // After
  observable.subscribe({ next: data => {}, error: err => {}, complete: () => {} });
  ```
- String-based `loadChildren` syntax fully removed — not used in this codebase, no action needed

### 14 → 15

TypeScript 4.8. Angular Material 15 rewrites components on MDC — this is the most significant Material migration step.

```bash
ng update @angular/material@15
```

- Expect visual changes and some API changes to Material components
- CSS overrides and custom theming will likely need updates
- Run the schematic and then manually audit each Material component used

---

## Phase 3 — Angular 15 → 17

### 15 → 16

TypeScript 5.0. Signals introduced (optional to adopt).

### 16 → 17

TypeScript 5.2. New block template syntax (`@if`, `@for`, `@switch`) becomes available but is not required.

- Migrate the application builder in `angular.json` from the Webpack-based builder to the new builder:
  - `@angular-devkit/build-angular:browser` → `@angular-devkit/build-angular:application`
- Zone.js upgrades to 0.14.x at this point

---

## Phase 4 — Angular 17 → 21

These steps are relatively smooth; the heavy lifting is done.

```bash
ng update @angular/core@18 @angular/cli@18 @angular/material@18
# repeat for 19, 20, 21
```

- TypeScript reaches 5.4+ by Angular 21
- At Angular 21: upgrade Zone.js to 0.15.x, ensure all `@types/*` packages are current

---

## Phase 5 — Modernization (post-migration)

Not required to complete the upgrade, but significantly improves maintainability:

1. **Standalone components** — migrate the monolithic `AppModule` and 24 component declarations:
   ```bash
   ng generate @angular/core:standalone
   ```

2. **Lazy-loaded routes** — split into feature areas (tables, SQL, graph, tags) to reduce initial bundle size

3. **New template syntax** — replace `*ngIf`/`*ngFor` with `@if`/`@for` block syntax via migration schematic:
   ```bash
   ng generate @angular/core:control-flow
   ```

4. **TypeScript strict mode** — enable `"strict": true` in `tsconfig.json` incrementally; start with `strictNullChecks`, fix all errors, then add remaining strict flags

5. **Signals** — consider migrating service observables to signals for simpler reactivity in new code

---

## Risk Summary

| Risk                                                | Mitigation                                                                                  |
|-----------------------------------------------------|---------------------------------------------------------------------------------------------|
| `ngx-chips` replacement changes tag-input UX        | Build Material chip replacement in Phase 0 and validate with users before upgrading Angular |
| Angular Material 15 MDC rewrite breaks styles       | Allocate dedicated time for CSS/theming fixes at the 14→15 step                             |
| `@swimlane/ngx-graph` v7→v8 API changes             | Spike the graph component upgrade independently at end of Phase 1                           |
| Accumulated broken state across 13 `ng update` runs | One git branch per major version; only merge when tests pass                                |
| RxJS 7 subscribe deprecation (~10–15 call sites)    | Global search for `.subscribe(` and fix all 3-argument forms before upgrading RxJS          |

The highest-leverage investment is **Phase 0** — clearing the abandoned dependencies before any version bumps reduces the chance of getting stuck mid-migration.
