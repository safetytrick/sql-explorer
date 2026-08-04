# Tag selection component UI cleanup

> **Status: implemented 2026-08-04, branch (b) — Material dropped
> entirely.** `TagViewComponent` rebuilt in the sidebar's chip vocabulary:
> colored chips via `TagColorsService`, real `×` remove control, wrapping
> layout, debounced autocomplete suggestions excluding already-applied tags
> (behavioral spec added using Michael's `COMMON_TEST_PROVIDERS` + the
> service stub). The other Material consumer, `AdminSourceListComponent`'s
> `mat-selection-list`, was ported to a plain checkbox list (selection state
> in a `Set`, Apply/Cancel semantics preserved). With no consumers left:
> `@angular/material` + `@angular/cdk` removed from package.json, the
> `indigo-pink.css` prebuilt theme removed from angular.json, all Material
> module imports/providers (incl. the snack-bar options) removed from
> main.ts, and the `.mat-app-background` specificity override in styles.css
> deleted (moot without the theme — `app-root` keeps the layout rules).
> `provideAnimations()` and `@angular/animations` were kept (ngx-graph may
> use them; removing is a separate legacy-ui step). Verified: eslint clean,
> `src/app` tsc clean, AOT build green.

## Current state

`TagViewComponent` (`ui/src/app/tag-view/`) is the tag chip editor used on
the table lens, table view, sql view, and tag pages. Three reported
problems, all diagnosed:

1. **All-white widget.** The app ships Material's prebuilt *light* theme —
   `angular.json:33` includes
   `node_modules/@angular/material/prebuilt-themes/indigo-pink.css` — while
   the rest of the UI is themed via the CSS custom properties in
   `ui/src/styles.css` (`--bg`, `--text`, …). Material chips/autocomplete
   render on their own white surface colors.
2. **"ca" instead of an ×.** The remove button is
   `<mat-icon>cancel</mat-icon>` (`tag-view.component.html:7-9`), which
   relies on the *Material Icons ligature font* — and `src/index.html` loads
   only Roboto (`:10-12`), **not** the Material Icons font. The literal text
   "cancel" renders and gets clipped to "ca" by the chip's icon sizing.
3. **Chips don't fit the page.** `mat-chip-grid` default layout + the chip
   input in constrained columns (table view right column, lens rail);
   `tag-view.component.scss` does no wrapping/sizing.

## Interpretation / options

- **(a) Patch Material in place (S):** replace `<mat-icon>cancel</mat-icon>`
  with a plain `×` character (or an inline SVG — no font dependency), add a
  theme block styling `mat-chip-row`/`mat-autocomplete` from the app's CSS
  variables (the workbench already overrides some Material surfaces —
  check `styles.css` for the existing pattern), and allow chip wrapping.
- **(b) Drop Material here (M):** the schema-tree sidebar already has a
  hand-rolled chip vocabulary (`.tag-chip`, suggestions list) using
  `TagColorsService.chipStyle` — port TagViewComponent to that, gaining
  tag colors (the Material chips ignore tag colors today) and deleting the
  page's last heavyweight Material dependency (also helps the legacy-ui
  item's goal of dropping Material/Bootstrap).

## Proposed approach

(b), reusing the sidebar's chip styles and `TagColorsService`: colored
chips with a real `×`, wrap naturally, dark-theme native, autocomplete via
the same suggestion-list pattern as the sidebar tag filters. Keep the
`AnalyzedSqlService` add/remove/autocomplete calls unchanged. Extract the
shared chip styles into a small SCSS partial or component so sidebar and
tag view stay consistent.

## Open questions

- Any other Material-dependent widgets worth keeping the theme for? If the
  explain view's Material table is gone (it is) and tag-view drops chips,
  `indigo-pink.css` and possibly `@angular/material` itself may be removable
  — fold into legacy-ui or do here?

## Effort: S for (a); M for (b) including the shared chip extraction.
