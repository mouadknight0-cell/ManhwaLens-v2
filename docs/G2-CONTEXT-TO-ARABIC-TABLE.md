# Phase G.2 §6 — Contextual fact → possible Arabic effect

This table is a statement of *what the code does today*, not a plan. Every row was read back out of
`app/src/main/java/com/manhwalens/app/context/ContextBridge.kt` and its tests. Where a fact type has
no rule, the row says so and names the code that blocks it.

The distinction that matters throughout: **a fact being held is not a fact being used.** `ActionableFact`
carries a `realizable` flag precisely so a report can tell those two apart instead of counting both as
understanding.

Legend for the last column:
- **live** — implemented, tested, and reachable in `C_GATED`.
- **blocked** — recognised and refused on purpose, with a stable reason code.
- **absent** — no analyser produces this fact at all yet.

| Fact type | Possible Arabic effect | Evidence required | Min confidence | Transformation | Abstain when | Status |
|---|---|---|---|---|---|---|
| Pronoun referent | Chooses *who* is meant; feeds gender, agreement, and name consistency downstream | `NEARBY_DIALOGUE` or stronger (`EXPLICIT`, `STORY_MEMORY`); never `HYPOTHESIS` | 0.60 | **A:** substitute the referent name for the single object/possessive pronoun in English, then translate the rewrite | pronoun is not object/possessive · referent is not a single-token name · pronoun occurs 0 or >1 times in the sentence · evidence rank weaker than nearby dialogue · confidence < 0.60 | **live, one source only** — see the three conditions below |
| Speaker gender | Arabic pronoun choice + verb/adjective agreement (feminine `ـت` vs masculine) | `STORY_MEMORY` gender claim for the *speaker* | — | **B:** would require locating the Arabic verb/pronoun span and inflecting it | always, today: `GenderRealization.blockedReason` — Arabic 1st-person past is gender-neutral (`أصبحتُ`), so there is nothing to inflect; 3rd-person has no alignment anchor in the Arabic text | **blocked** (`first_person_arabic_is_gender_neutral`, `no_alignment_for_third_person`, `gender_unresolved_preserved`) — *finding, not bug* |
| Glossary / work term | A fixed Arabic rendering for a world-specific term | `EXPLICIT` (term comes from the glossary itself) | 1.0 | **B:** replace the ML Kit rendering of that term with the glossary Arabic | always, today: the Arabic translation is a black box with no term-to-span alignment, so there is no safe place to write | **blocked** (`no_arabic_anchor`) |
| Lexical sense (polysemy) | Wrong sense → wrong Arabic word | needs a disambiguating context claim | — | **A** (rewrite the source phrase) or **B** (choose the Arabic equivalent) | no analyser produces a sense claim yet | **absent** |
| Idiom | Translate the meaning, not the words | needs idiom recognition + a meaning mapping | — | **B/phrase:** replace the literal rendering with the idiomatic Arabic | no idiom detector exists | **absent** |
| Entity identity (name variants) | Consistent name + pronoun + title across a page | needs an entity-resolution claim | — | downstream of pronoun referent | no entity resolver exists | **absent** |
| Sentence relationship to prior dialogue | Interpret the reference/reply (e.g. "That day") | needs dialogue-relation claim | — | **A:** contextual source rewriting | no analyser produces it | **absent** |
| Ellipsis recoverable from context | Restore the elided meaning | needs a claim that the elided span is determined | — | controlled reconstruction | nothing produces the claim | **absent** |

## What "live" actually requires today — three conditions, all of them narrow

Writing the §11 tests was what turned this from an assumption into a measurement. A pronoun is
clarified **only** when all three hold:

1. **The referent is recorded with the pronoun itself as the memory key.** `ContextBuilder.memoryEntryFor`
   looks up `MemoryKind.NAME` with `question.subject` — the pronoun surface — as the key. A record
   therefore has to read `him -> Jin`. A record keyed by the character (`jin -> Jin`) is a name the
   engine already knows, not an antecedent, and settles nothing. Verified both ways: the first shape
   yields `settledAt=STORY`, the second yields `settledAt=NONE`.
2. **The pronoun is an object or possessive form.** `objectPronouns = {him, her, them}`, so a
   *subject* pronoun (`he`, `she`) can never be clarified even with a perfect referent. This is not
   an oversight: rewriting the subject changes the sentence's own syntax, and the bridge only
   performs single-token swaps it can verify.
3. **The name is a single token** (`looksLikeName`). "Jin Woo" is refused, and that limitation is
   stated rather than hidden.

Conditions 1 and 2 mean the mechanism is correct but its input surface is very small. That is the
honest state of the bridge: **the plumbing now carries a settled referent to the Arabic path, and
what remains scarce is the set of sentences the engine can settle at all.**

## A dead wire that this phase found and fixed

`ContextBuilder.tryAnswer` settled referent questions through story memory and then returned only a
`ContextLevel`. The answer itself was discarded, so `frame.pronounReferents` — the *only* field
`ContextBridge` reads for referents — was never populated by the production path. `settledAt` could
report `STORY` while the bridge saw an empty frame and returned `NO_ACTIONABLE_SEMANTIC_FACT`.

This was found by writing the §11 test and watching it fail against a real `ContextTranslation.run`
call, not by reading the code. It is fixed: `tryAnswer` now records a `Claim` carrying the value, the
evidence kind and the confidence. The fix was verified by reverting it — 3 of the 5 new tests fail
without it and pass with it.

## Why so few rows are live

The constraint in the phase brief is that every change must be provable, and proof has two halves:
the semantic fact must be well-evidenced, *and* the edit must be locatable in the output. Pronoun
clarification is the only fact type that satisfies both today, because it is the only one where:

1. the engine produces a typed claim with an evidence kind and a numeric confidence, and
2. the affected English span is a single token that can be found unambiguously in the source.

Gender fails (2) on the Arabic side: Arabic first-person past tense does not mark gender at all, so
for the exact case that motivated the phase ("That day, I became a mage." with a known female
speaker) there is *nothing grammatically available* to change. Rendering it as feminine by inventing
an inflection would be fabricating Arabic. That refusal is recorded as a result, not hidden as a gap.

Terms fail (2) because the Arabic string is produced by a black-box engine and the bridge has no
alignment from an English token to a span of that output. Option B's own rule applies: if the Arabic
span cannot be located confidently, do not edit.

## The rule that keeps this honest

`ContextBridge.decide` never returns `CANDIDATE_ACCEPTED`. It stops at the highest state the evidence
justifies and hands the rewrite to the caller that actually translated it. Acceptance is a statement
about Arabic output, and only the caller holding that output may make it. This is enforced in code,
not by convention: the accepted flag is set in `ContextTranslation.run` after verification passes,
and `CONTEXT_RESOLVED → CANDIDATE_ACCEPTED` is therefore not a shortcut that can be taken by mistake.

## Tests backing each live/blocked claim

| Claim | Test |
|---|---|
| A resolved pronoun produces an executable rewrite | `aResolvedPronounProducesAnExecutableRewrite` |
| The rewrite changes one span and keeps its provenance | `theRewriteIsTheOnlyThingThatChangedAndItKeepsItsProvenance` |
| A hypothesis never becomes an edit | `aHypothesisNeverBecomesAnEdit` |
| Story memory alone is too weak | `storyMemoryAloneIsTooWeakToChangeTheSentence` |
| A low-confidence claim does not qualify | `aLowConfidenceClaimDoesNotQualify` |
| A non-name referent never replaces a pronoun | `aReferentThatIsNotANameNeverReplacesAPronoun` |
| An ambiguous pronoun position is refused | `anAmbiguousPronounPositionIsRefused` |
| Reflexive/unsupported pronouns are refused | `aReflexiveOrUnsupportedPronounIsRefused` |
| Gender is recorded and reported unusable, not silently dropped | `resolvedGenderWithNoExecutableRuleReportsNoActionableFact` |
| Glossary knowledge is recorded, not swapped blindly | `glossaryKnowledgeIsRecordedAsUnusableRatherThanSwappedBlindly` |
| Resolution is never reported as acceptance | `resolvedIsNeverReportedAsAccepted` |
| A clear sentence pays no story-memory cost | `aClearSentenceNeverPaysForContextItDoesNotNeed` |
| The verifier accepts only a single-token swap | `theVerifierAcceptsonlyASingleTokenSwap` |
| The verifier rejects an extra word smuggled in | `theVerifierRejectsAnExtraWordSmuggledIn` |
| A rewrite that changes nothing is not a candidate | `aRewriteThatChangesNothingIsNotACandidate` |
| A possessive is clarified and keeps its capital | `aPossessiveIsClarifiedAndTheNameKeepsItsCapital` |

Note on the last block: the test that pins *settled fact → translation seam* is not in
`ContextBridgeTest`. It lives in `ContextModePolicyTest` and `EvidenceCaptureTest`, which exercise
`ContextTranslation.run` and the mode gate. That split is intentional — `ContextBridge` decides, the
translation layer accepts or falls back, and each is tested where it lives.
