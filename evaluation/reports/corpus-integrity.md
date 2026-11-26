# Report A — Corpus integrity

- Schema version: `1.0.0`
- Dataset fingerprint: `5fb61a318d51c91d9e017bf4b09a09b8…`
- Total cases: **40** (unique ids: 40)
- Duplicate ids: none
- Errors: 0 · warnings: 55
- Provenance complete: True

## Reference review status

Only `reviewed` references with a named reviewer and timestamp may take part in
verified quality scoring. Nothing in this corpus has been upgraded by tooling.

| status | cases |
| --- | --- |
| pending_human_review | 40 |

Valid for scored evaluation: **0** of 40.

## OCR quality strata

| stratum | cases |
| --- | --- |
| clean_ocr | 38 |
| unknown | 2 |

## Text length

| length | cases |
| --- | --- |
| unclassified | 40 |

## Semantic categories

| category | cases |
| --- | --- |
| complex_grammar | 1 |
| dialogue_dependent | 1 |
| emotional | 9 |
| idiom | 5 |
| implicit_meaning | 3 |
| incomplete_sentence | 1 |
| pronoun_unclear_referent | 5 |
| sarcasm | 2 |
| unclassified | 13 |

## Context groups

| group | orders |
| --- | --- |
| g31-thought | 1, 2 |

## Matrix coverage

| dimension | value | status |
| --- | --- | --- |
| text_length | short_phrase | **gap** |
| text_length | single_sentence | **gap** |
| text_length | medium_sentence | **gap** |
| text_length | long_sentence | **gap** |
| text_length | multi_line | **gap** |
| text_length | exceeds_effective_coverage | **gap** |
| semantic_category | plain | **gap** |
| semantic_category | idiom | covered |
| semantic_category | slang | **gap** |
| semantic_category | figurative | **gap** |
| semantic_category | sarcasm | covered |
| semantic_category | emotional | covered |
| semantic_category | implicit_meaning | covered |
| semantic_category | ambiguous | **gap** |
| semantic_category | elliptical | **gap** |
| semantic_category | incomplete_sentence | covered |
| semantic_category | complex_grammar | covered |
| semantic_category | unusual_word_order | **gap** |
| semantic_category | name_or_title | **gap** |
| semantic_category | pronoun_unclear_referent | covered |
| semantic_category | negation | **gap** |
| semantic_category | emphasis | **gap** |
| semantic_category | dialogue_dependent | covered |
| context_dependency | isolated_bubble | covered |
| context_dependency | depends_on_previous | covered |
| context_dependency | depends_on_multiple_previous | **gap** |
| context_dependency | depends_on_following | **gap** |
| context_dependency | multi_bubble_sequence | **gap** |
| context_dependency | changes_with_context | **gap** |
| ocr_quality | clean_ocr | covered |
| ocr_quality | minor_corruption | **gap** |
| ocr_quality | moderate_corruption | **gap** |
| ocr_quality | severe_corruption | **gap** |
| ocr_quality | truncated_text | **gap** |
| ocr_quality | missing_lines | **gap** |
| ocr_quality | merged_lines | **gap** |
| ocr_quality | fragmented_text | **gap** |
| ocr_quality | unknown | covered |
| corruption_type | lost_words | **gap** |
| corruption_type | duplicate_words | **gap** |
| corruption_type | substitution | **gap** |
| corruption_type | omission | **gap** |
| corruption_type | fragment | **gap** |
| corruption_type | incorrect_line_splitting | **gap** |
| corruption_type | incorrect_line_joining | **gap** |
| corruption_type | partial_capture | **gap** |
| corruption_type | missing_line | **gap** |
| corruption_type | merged_line | **gap** |
| corruption_type | unknown | **gap** |

Coverage gaps: **37** of 49 matrix cells.

