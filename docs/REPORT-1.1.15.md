# v1.1.15 — phone-report regressions

Input: user supplied v1.1.14 complete report (761,952 bytes). Do not commit the private report. Its samples contain 92 v1.1.13 records and 70 v1.1.14 records; the latter contain 66 without technical error, 2 TR-INVALID-OUTPUT, 1 OCR-UNCERTAIN, 1 OCR-CLIPPED. These are retained samples, not an accuracy benchmark. Successful Arabic validation does not establish correct meaning.

## Save reliability

The user's saved attachment was empty, whereas sharing produced a complete report. No DIAG-EXPORT exception appears in that report; the exact cause is not established. Previously generation happened only after the document picker created an empty destination. Now prepare the private report first, preserve its path with saved instance state across picker recreation, and copy it to the selected document. Reopen the destination and compare byte count plus streaming SHA-256 before announcing success. If readback is unsupported or mismatches, report failure rather than claim a successful save. Keep the prepared file for retry on failure. Cancellation of the picker deletes the prepared file. No data/history/settings clearing.

This reduces the vulnerable create-before-generation window; it does not make arbitrary Android document providers transactional, nor eliminate every process-kill scenario. Physical Xiaomi/provider testing is still needed.

## Translation quality: bounded changes

- Extend conservative zero-to-O allowlist with DOCTOR and TOO, retaining raw OCR and leaving numbers, names, suffixed tokens, and ambiguous YCu unchanged.
- Reviewed whole-utterance first-person `I'LL HELP YOU` / `I WILL HELP YOU` -> `سأساعدك`. No substring replacements, negation changes or other-speaker rewrites. Existing name-detection gating remains.
- Tests explicitly reject applying the reviewed utterance to negation, quoted longer sentences, different speakers or corrupt YCu input.

These are narrow verified regressions, NOT a general contextual translation upgrade. ML Kit tense, idioms and gender issues remain; no new online model, upload of screen text, speed gain or general semantic accuracy claim. More aggressive spell guessing is deliberately avoided without image evidence.

## Verification

Build and tests must run on GitHub. See linked run evidence in the PR/release. No physical-device save/share, upgrade/data preservation or full translation A/B has been executed here.

Executed at 9cfa890: JVM/compile/debug build passed https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44639310197 ; Android 14 existing OCR/circle/complete-report instrumentation passed https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44639310191 . This instrumentation does not exercise the external document-picker/provider save flow.
