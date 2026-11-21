# Phase 3 · Arabic reader-quality benchmark

**Status: prepared, not executed.** There are no observed Phase 3 translations. The Arabic in `dataset.tsv` is an independent editorial reference, not machine output, a correction, or a human-panel rating.

## A. Environment and baseline

HEAD `a0db02b822ce93edf3aa6b6d9758ebdc7db2bb2a` (Phase 2); restore `5f57327` has an identical tracked tree to v1.1.30 (`488c40427fe5673a833cdc2cd65a598db7965234`). Initial tracked tree clean; `deliverables/` already untracked. No build installed or run. The local sandbox has no adb/emulator on PATH, Android SDK environment, `/dev/kvm`, or USB device bus. No accessible Android device, OS/API, installed model revision, model readiness, device settings, or cache contents could be verified. ML Kit Translate dependency: 17.0.3, not a model version. No download requested, remote job dispatched, or reader data transmitted. Previous CI is not a quality observation.

**Replay conditions.** Use the same debug build at this commit, on one Android device already provisioned for English→Arabic offline. Disable connectivity before starting. Record device/ABI, OS/API, build hash, model installation/readiness and model identifier if exposed; do not infer a version from the dependency or erase the installed model. Use stock `ReadingGlossary.murim + ReadingGlossary.defaults`, no additional confirmed names, no user corrections, stock reader settings. Record these settings rather than assuming defaults on an existing reader profile. Use a dedicated test profile. Restart the process between independent cases/context arms to clear the in-memory cache; retain it within each continuous scene. Document any page reuse. Do not time or score model installation.

## B. Dataset and procedure

`dataset.tsv`: 40 clean inputs, nine scene groups. 01–38 are synthetic dialogue; 39–40 are visually transcribed from the repository's `report25-question.png` and `report25-watching.png` comic crops, not independently authenticated published quotations. The last group contains independent controls, not evidence they were consecutive bubbles. Scene notes are for reviewers, not model prompts. References were written without consulting app output for this benchmark. Accept equivalent Arabic, including unvocalized gender-ambiguous wording when appropriate. Have an independent bilingual reader score before adjudication; no such review has yet occurred.

1. **Clean:** In a local debug instrumentation driver call the unchanged `translateBlock` with the exact source, reliable text region, confidence 1, normal casing, no retry overrides; pass the prior eligible line only within continuous scenes. For context pairs use identical region and prior text (null), vary only the reviewer scene. Keep 39 and 40 independent. Capture normalized source, shield result and actual `modelInput`, route, native task result (suffix only for hybrid), and block output via debugger on this build; no replacement callback. For local routes native fields are N/A; record local match text separately from final. For rejection record the actual status. Do not equate a returned block with visible text.
2. **OCR:** Decode the two named repository PNGs unchanged; record dimensions, crop/transform, raw OCR lines/boxes and confidence, repaired English, grouped regions, route and output for each. Compare against 39/40 clean controls. Separately stress text preparation with synthetic corruptions of 37: `Is everyone saft?` (substitution), `Is everyone sfe?` (deletion), `Is every one safe?` (broken word), `Is everyone sa` (clipped), `Is everyone safe.` (uncertain punctuation). Use `Retreat!` (10) as single-command control. Never count these seeded strings as real OCR observations.
3. **Display:** On-device, find the original local reader pages containing 39/40 and verify the source visually **before** counting them; full-page correspondence has not been established here. Do not use isolated crops as a substitute for full reading workflow. If unavailable mark unavailable. Record private local image hashes, viewport, orientation, scale, insets, capture settings and region coordinates. Annotate all eligible bubble boxes, intended text and reading order before enabling capture. Capture the displayed state locally; align text to each box, including omissions, extra and swapped overlays, truncation and clause boundaries. Compare with layer 2 where the same region is available. Do not upload screenshots. To test the two-bubble thought separately, use a clearly labelled synthetic page for 31–32, not a claim about the original comic.

`observations.csv` is a blank keyed ledger for the three layers; join by ID for reference, original and context. Append rows for extra annotated bubbles and stress probes with distinct identifiers. Blank measurement fields mean unobserved, not empty app output. Preserve originals; reviewer corrections occupy a separate column.

## C. Results and scoring contract

| Layer | Planned primary units | Executed | Quality results |
|---|---:|---:|---|
| Clean translation | 40 lines | 0 | Not measured |
| OCR + translation | 2 crops | 0 | Not measured |
| On-screen display | 2 target bubbles pending page verification | 0 | Not measured |

Successful, rejected and missing translation counts, route shares, completeness, repetition preservation, OCR error and assignment accuracy are **not measured**, not zero. No scores or error percentages can be computed.

For each executed output rate meaning, naturalness, scene interpretation, grammar/pronouns, terminology, and tone independently: 0 failed, 1 significant problem, 2 acceptable with minor issues, 3 accurate and natural. Require a reason per 0/1; mark inapplicable dimensions N/A. Do not upgrade wrong meaning for fluency. Flag reversed meaning, lost negation, wrong actor/recipient, invented action, important omission, wrong relationship, established-gender error and fluent severe mistranslation separately. Severity: minor surface issue; moderate noticeable but recoverable; major wrong or missing substantive content; critical materially misleading under the listed flags. Judge intentional silence/rejection separately from bad Arabic.

Report per layer: eligible/attempted/returned/rejected/missing; per-dimension mean, median and rated n; critical meaning errors / reviewed items, grammar scores ≤1 / grammar-rated items, inconsistent term occurrences / reviewed comparable term occurrences (group by sense). Report returned-output quality separately from coverage. Count complete meaning units / expected units including omissions; report repetition-preserving outputs / emphasis-eligible cases (07). Route counts / attempts, including rejection as a separate outcome; cache hits separately from inference. OCR character error = summed character edit distances / summed gold characters after identical whitespace normalization; also report exact-line failures. Display assignment = correctly matched readable translations / annotated eligible bubbles, with swaps, truncation and missing separately tagged. Never pool layers. Even when run, this is a diagnostic set, not a population estimate.

## D. Worst translation cases

None can be identified without actual outputs. For every significant defect retain ID, intended reading/reference, exact observed output, why it changes meaning, severity, first divergent trace, fixability without an engine change, smallest correction and regression case. For hybrid results inspect the local prefix and actual native suffix independently.

## E. Root cause distribution

Unavailable for all stages. Attribute the first evidenced fault: OCR_ERROR, OCR_REPAIR_ERROR, TEXT_SEGMENTATION_ERROR, CONTEXT_LOSS, NAME_OR_TERMINOLOGY_ERROR, LOCAL_RULE_ERROR, NATIVE_MT_ERROR, ARABIC_GRAMMAR_ERROR, POST_PROCESSING_ERROR, BUBBLE_ASSIGNMENT_ERROR or DISPLAY_ERROR; use UNKNOWN when traces do not distinguish causes. Compare gold to raw OCR, repaired input to gold, raw Arabic to final, and block to displayed region. A grammatical symptom alone does not establish an engine defect.

## F. Paired context

Pairs 12/13/14 (woman/group/unknown), 06/11 (grip/wait), 21/25 (end relationship/end argument), 08/16 (danger/score) keep English byte-identical. Record actual route/input/output for each arm; assess suitability separately even if strings match. Identical Arabic can be valid in more than one scene. Source inspection: `TranslationClient.translateBlock` passes previous text to local matching, while the native call receives `modelInput`, not an image, speaker label or reviewer scene. Joining adjacent bubbles can change input; it does not supply speaker labels. These are architectural facts, not findings that any pair actually produced identical or wrong Arabic.

## G. Remaining strengths

No reader-quality strengths established by this run. Phase 2 verified structural protections for confirmed names and repetitions. Retain them as controls; they do not demonstrate idiomatic Arabic.

## H. Ranked Phase 4 recommendations

No production improvement is rankable on these data. The first evidence-gathering priority is the 40 clean lines on a local model-ready device, followed by matched OCR and display captures. This isolates native or rule errors from reading-workflow failures before choosing any implementation. Do not recommend a new engine, context architecture or dictionary from missing measurements.

## I. Verification

Only benchmark documentation, dataset and blank observation ledger added. No production, test, workflow, build, model, cache or release configuration changes. No historical output recycled as current evidence.
