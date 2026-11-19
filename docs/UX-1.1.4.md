# v1.1.4 — capture controls and scoped diagnostics

## Evidence supplied
No new screenshot attachments were available in this request. The supplied v1.1.3 samples and logs are direct evidence, not screenshots. Prior screenshots showed the older crowded control panel/overlay layout and cannot establish current v1.1.4 appearance.

Samples show `TER- RIBLE` reaching translation, literal ML Kit output, and repeated samples across separate journey IDs. Source inspection found RegionMerger converted OCR line boundaries to spaces before SentenceReconstructor.joinLines could repair hyphenation. RegionMerger now calls joinLines while the boundaries still exist; ordinary in-line `well-known` is preserved. This does NOT fix ML Kit's semantic handling of idioms or pronouns, and no Arabic templates were hardcoded.

Logs show partial translation reported as CAP-2014 despite TR-INVALID-OUTPUT block errors. Benchmark outcome logging now distinguishes cancellation (INFO), partial translation (WARN with actual block error code), timeout and failure. Process warm-up advances after the first model-ready journey, even if it is partial; older stored cold flags are not rewritten. Numeric/punctuation-only source regions pass through rather than being rejected solely for having no Arabic. Unknown failed input cannot be reconstructed from content-free logs; validation now records character counts only.

## Capture workflow
One authoritative SessionPhase in CaptureManager: INACTIVE / STARTING / ACTIVE / STOPPING / FAILED. Main UI and floating controls subscribe to it. ACTIVE is only set when the projection/display pipeline is ready. STARTING covers Android consent; STOPPING blocks new consent until teardown. Failed startup stays FAILED after teardown, and can retry. Duplicate consent requests are rejected centrally.

Floating connected controls now have visible Start Capture / Stop Capture labels, state text, accessible descriptions and state tint. Start opens the transparent CaptureConsentActivity directly, not MainActivity. Existing valid sessions can resume Live without requesting duplicate consent. Stop invalidates outstanding consent, tears down resources, and does not start a needless empty service when there is no active session. Disable and Open App remain available. Android's system consent cannot be bypassed; OEM background-activity policies still need device validation.

## Settings organization
Collapsible groups: Translation/model, Floating icon + Capture Session, Live behavior, Names, four diagnostic sections, Advanced. Download/verify lives with Translation. No pretend quality sliders or duplicate Live toggles were added. Names use flexible rows rather than three fixed-width chips. Destructive name/history actions require confirmation. Model/session/Live status comes from actual state flows. Actions have at least 48dp targets where applicable and labels can wrap. RTL, narrow screens, large fonts and touch behavior still require actual device review.

## Copy/reset data boundaries
| Section | Copy/View/Share | Reset |
|---|---|---|
| Benchmark | Complete retained human-readable report + raw JSON, version/device/status header | Benchmark store and this section's exported TXT only |
| Translation | Retained source/output pairs, per-block duration, error code, trace, language pair/method | TranslationSamples and its exported files only; NOT history, glossary, model or preferences |
| Errors | Retained ERROR/FATAL events with context/stacks | Error events in disk logs and memory, plus error-section exports; ongoing failures can appear again |
| Bugs | All retained manual issue files and automatic bug snapshots | Exact issue-*.txt and bug-*.txt archive files plus this section's exports; NOT live event logs |

Old bug snapshots can still contain historical benchmark/error text after another section is reset: this is intentional independent-archive behavior. Active sessions are not cancelled by a diagnostic reset. New data from active work can repopulate a cleared section.

All sections share Copy / Reset / View / Share TXT, IO-backed actions, feedback and reset confirmation. Clipboard text exceeding the safe per-transaction size is copied losslessly in ordered parts with explicit next-part controls and a full-TXT share option; no silent truncation. Copying arbitrary-sized text in one Binder clipboard transaction is not reliable on Android. Reset errors surface rather than showing false success. Benchmark writes are atomic and reset deletion failure is no longer swallowed.

## Verification
Added JVM tests for lossless Arabic/emoji clipboard chunking, bug-reset file boundaries, duplicate consent/start-stop-failure transitions, and OCR line-end dehyphenation. Existing tests/builds run on GitHub only. A device is not attached: no actual clipboard, sharing, permission prompt, start/stop resource, settings navigation, layout/accessibility, upgrade/data-preservation test has been executed here. No new latency measurements or fluent Arabic quality claims.

Device checklist: start/stop solely from bubble, deny then retry permission, repeat quick start taps, cancel permission with Stop, verify state text matches projection notification, disable/re-enable bubble, Copy each small/large report and reconstruct chunks, Reset each section during active and inactive sessions, verify unrelated data survives, rotate and use large font scaling, verify metadata/error codes in a fresh export.
