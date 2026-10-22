# Design system

The panel uses two token layers and ordinary HTML controls. There is no UI library, font download, icon pack, or generated theme. The approved mobile flow uses a light surface, graphite text and deep teal actions.

| Layer | Source | Owns |
| --- | --- | --- |
| Primitive | [primitives.css](../src/design-system/primitives.css) | Literal colors, space/size scales, radii, strokes, font families, type sizes, weights and opacity |
| Semantic | [semantic.css](../src/design-system/semantic.css) | Role aliases such as color-action, color-danger, space-field, control-min-height and text-body; values reference primitives |

[index.css](../src/design-system/index.css) loads primitives, semantic aliases, then [base.css](../src/design-system/base.css) and [components.css](../src/design-system/components.css). Component rules consume semantic tokens only. Add or adjust a primitive when a raw scale is missing; map it to a role before using it in a control. Do not duplicate token values in Vue templates or scoped styles.

The base layer supplies system typography, labeled inputs, selects, textareas, native radios and focus treatment. Component classes supply buttons, panels, disclosure controls, profile headers/editors, badges, remote choices and feedback. These are small CSS recipes used by the real panel rather than wrapper components around every HTML element.

## Layout and accessibility

- Body text is 16 px, helpers/code 14 px, section headings 20 px and the application heading 24 px at the default browser font size. Rem values respect browser scaling.
- Controls have a 48 px minimum target. A default radio's label and the adjacent editor disclosure have separate hit areas. Long labels/IDs wrap and rows grow; there is no fixed Save footer or horizontal clipping.
- Semantic action, body, secondary and error colors have white-background contrast ratios of 6.36:1, 15.69:1, 7.00:1 and 7.52:1 respectively. Input boundaries are 3.61:1. Focus is visible for keyboard navigation, including inside a clipped remote-options border.
- A selected system default uses a native radio plus Default text. A remote option uses pressed state plus Selected text. Dirty, pending, success, disabled and error states have explicit English text/semantics in addition to color.
- Key configuration starts collapsed. HTML tables retain labels above controls. Advanced fields are disclosed inside each profile. The numeric Preview pair wraps when space is insufficient; the overall document stays one column.
- The Content Security Policy permits bundled styles from the application origin. It continues to reject inline styles, remote styles/images and untrusted scripts. Vite production output is used for browser and Android checks.

## State ownership

`App.vue` owns one draft per stable editor key and compares it with that row's saved baseline. Native profile upsert/removal is keyed by the persisted profile ID, independently of its Fish Reference ID. Saving one row validates that row only, refreshes its saved baseline and keeps its editor open. It cannot submit or replace another draft. Mutations are serialized while unrelated fields remain editable, avoiding stale whole-configuration replies.

The native store applies each change to its latest encrypted configuration inside a short local read/write lock. Key saves, default selection and row removal preserve unrelated drafts. Errors keep local edits. Default selection has a pending radio value separate from the confirmed Default badge and rolls back on failure. Dirty/new non-default rows must be saved before selecting them. The currently saved default remains effective while its draft changes.

The **Use system language** switch uses a native checkbox with the switch role and a labeled 48 px touch target. It defaults to off (`unset`), sending an empty language for all voices and previews; on (`system`) follows Android's language. Changes save immediately without submitting the key or voice drafts, and failures roll back to the saved choice. Text describes both states alongside the visual switch.

Preview uses a saved profile. Its Language field is disabled while **Use system language** is off and retains the selected locale for when the switch is turned on. A dirty selected profile must be saved; an unrelated draft does not block it. The global key remains write-only across the bridge and is never part of CSS, token files, browser storage or the build configuration.

## Verification

`npm run test:ui` builds the actual production assets and runs Playwright. Its bridge fixtures cover draft isolation, delayed and failed mutations, radio rollback, removal fallback, catalog behavior, active controls and layout at 320/360/390/412/1280 px. These fixtures do not establish real Fish access or protected native persistence. Android instrumentation separately exercises the actual bridge/store, concurrent keyed writes, capacity, system TTS behavior and real software-keyboard visibility. See [verification.md](verification.md) for current results.

Playwright configuration follows the [official web-server](https://playwright.dev/docs/test-webserver) and [fixture](https://playwright.dev/docs/test-fixtures) contracts. The tests can reuse the project's registered production preview on port 4173.
