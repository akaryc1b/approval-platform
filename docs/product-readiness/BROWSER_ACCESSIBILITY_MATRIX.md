# PC/H5 Browser and Baseline Accessibility Matrix

This page documents the bounded Product Readiness matrix tracked by Issue #137
under umbrella Issue #107.

## Entry points

```bash
pnpm demo:runtime:browser-accessibility:plan
pnpm demo:runtime:browser-accessibility:check
pnpm demo:runtime:browser-accessibility
```

The runtime command reuses the accepted `demo:quickstart` lifecycle. It does not
create a second backend, database, Seed, PC client, H5 client or automatic
Workflow.

## Governed matrix

The authority is:

```text
config/demo/browser-accessibility-matrix.json
```

The scenario identity is resolved through the existing Quick Start and
purchase-payment manifests rather than repeated in the browser scripts.

| Project | Runtime | Accepted scope |
| --- | --- | --- |
| `system-chromium` | locally discovered Chrome/Chromium | authenticated PC keyboard task/detail/dialog path plus PC/H5 critical surfaces |
| `bundled-firefox` | Playwright Firefox | PC/H5 critical-surface compatibility smoke |
| `bundled-webkit` | Playwright WebKit | PC/H5 critical-surface compatibility smoke |

Playwright WebKit is not real Safari evidence.

## What is checked

Every project proves that the same governed `DEMO-PP-0001` task is visible in
PC and H5, Chinese glyphs are rendered as distinct characters, selected
critical controls have programmatic names, and selected task/action text meets
the bounded contrast floor.

Chromium additionally uses only keyboard events after the existing local demo
login to:

```text
focus the PC task action
→ open task detail
→ focus the approve action
→ open the confirmation dialog
→ verify visible focus
→ close without approving
```

The slider-based local demo login is not part of the keyboard claim.

## Evidence

Every run writes to:

```text
.runtime/browser-accessibility/<run-id>/
```

The directory contains source identity, the matrix contract, per-engine JSON,
PC/H5 screenshots, Playwright traces, the matrix summary, runtime summary and
failure evidence when applicable. CI embeds a bounded integrity envelope in
the existing Vben artifact log; `.runtime` remains untracked.

## Accepted bounded claims

```text
PC_H5_CHROMIUM_COMPATIBILITY_BASELINE_PASSED
PC_H5_FIREFOX_COMPATIBILITY_SMOKE_PASSED
PC_H5_WEBKIT_ENGINE_COMPATIBILITY_SMOKE_PASSED
PC_AUTHENTICATED_KEYBOARD_TASK_FLOW_PASSED
BASELINE_AUTOMATED_ACCESSIBILITY_PASSED
PC_H5_CJK_RENDERING_MATRIX_PASSED
BROWSER_ACCESSIBILITY_MATRIX_PUBLISHED
```

The claims are emitted only after all three browser projects pass and the
existing Quick Start lifecycle records successful process, container, volume
and five-port cleanup.

## Explicit non-claims

```text
FULL_BROWSER_COMPATIBILITY_NOT_VERIFIED
EDGE_RUNTIME_NOT_VERIFIED
SAFARI_BROWSER_NOT_VERIFIED
IOS_SAFARI_NOT_VERIFIED
ANDROID_CHROME_NOT_VERIFIED
WECHAT_WEBVIEW_NOT_VERIFIED
WECHAT_DEVTOOLS_RUNTIME_NOT_VERIFIED
AUTHENTICATION_KEYBOARD_ACCESSIBILITY_NOT_VERIFIED
H5_KEYBOARD_TASK_NAVIGATION_NOT_VERIFIED
FULL_WCAG_CONFORMANCE_NOT_VERIFIED
SCREEN_READER_MANUAL_TEST_NOT_VERIFIED
PERFORMANCE_CAPACITY_NOT_VERIFIED
RPO_RTO_NOT_VERIFIED
MYSQL_8_4_NOT_VERIFIED
```

This matrix is a local Product Alpha baseline. It is not a production support
matrix, a manual screen-reader audit, a full WCAG conformance statement or a
real-device/browser certification.
