# User-supplied v1.1.1 device baseline

Source: user pasted benchmark report after v1.1.2 publication. App 1.1.1 (217262600), Xiaomi 23106RN0DA, Android 14, 720×1600. These are NOT v1.1.2 measurements or runs executed in the development environment.

| Metric (ms) | Cold: 18 regions / 18 blocks | Warm: 19 regions / 19 blocks |
|---|---:|---:|
| Capture T2→T4 | 4.555462 | 4.261539 |
| Image preparation | 8.593230 | 7.420000 |
| OCR | 979.505231 | 449.283154 |
| Grouping | 24.271616 | 31.531692 |
| Model readiness + translation T8→T11 | 27530.627156 | 1424.038077 |
| First ready T8→T9 | 26721.633233 | 679.253769 |
| Processing T2→T11 | 28560.520079 | 1922.390154 |
| First ready→UI marker T9→T10 | 4149.280385 | 379.153231 |
| First UI marker T2→T10 | 31900.806541 | 1556.759077 |
| Completion→final UI marker T11→T12 | 4057.590769 | 2074.093000 |
| Total T2→T12 | 32618.110848 | 3996.483154 |
| Process lifetime before request T0→T2 | 111220.732853 | 178271.977780 |

Warm trace t-mwnp0kk8-3, session 79f15716-89ea-4261-be84-62d1c31f9aec, started_at_ms 1795101383960.
Cold trace t-mwnoz4tp-2, session cc945148-7106-4959-b1b2-b57ab3f0f473, started_at_ms 1795101316909.

## Findings and limits
- Processing improved by 26638.129925 ms between these two *v1.1.1* runs. This is not evidence of a v1.1.2 speedup.
- The cold delay is concentrated before the first completed translation, not OCR or capture. T8→T9 consumes 26.722 s of the 27.531 s combined model/translation interval. The remaining T9→T11 is ~0.809 s. Without model/download/cache events, the causes cannot be distinguished: model readiness, first inference, task scheduling, timeout, and cache reuse remain hypotheses.
- Warm run has more regions yet completes much faster. Region count alone cannot explain the difference. Content is not available for equivalence checking.
- Cold T10 occurs AFTER T11 by 3340.286462 ms. Thus 'progressive' emission did not receive a corresponding early UI marker in this run. This does not prove actual screen presentation timing: markers are callbacks, and v1.1.1 allowed historical result callbacks to affect the global active journey.
- Warm T11→T12 still adds 2.074 s, exceeding its 1.922 s entire processing time. Investigate UI scheduling, result rebuilding, history/thumbnail work, foreground transitions and callback attribution. Do not label this measured GPU rendering time.
- Process-to-result values 143838.843701 and 182268.460934 ms include 111.221 and 178.272 seconds before each request. They are not capture-processing latency.
- cold=true means the first successful journey of the process, not independent proof of a download or cold model. Two samples are insufficient for percentiles or reliable performance claims.

## Follow-up on v1.1.2
Use the same page/scroll position. Export cold and repeated warm runs with recent diagnostic events. v1.1.2 adds M0/M1 for model readiness, preserves T8/T11 for processing, and binds UI marks to journey IDs. Compare stage timings, T2→T10 and T2→T12; inspect t12Inferred. New T2 includes request/consent delay, unlike older pipeline-entry timing, so compare stage timings as well as totals. Physical-device validation remains outstanding.
