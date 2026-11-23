"""Reference review workflow: a self-contained offline HTML review sheet.

Design constraints this had to satisfy
--------------------------------------
* The reviewer must be able to **correct the Arabic** before approving.
* No automatic approval, no invented reviewer, no generated timestamp. The tool
  therefore *cannot* mark anything reviewed by itself; it only collects what a
  human typed and stamps `reviewed_at` from the clock at save time, which is the
  moment the human acted.
* Nothing is generated. A blank reference stays blank; the reviewer may fill it,
  and doing so is still their explicit act.
* Pending and disputed states the reviewer did not touch must survive verbatim.

Output is a single HTML file with no network dependencies: no CDN, no fetch, no
external CSS. It works opened from the filesystem in a desktop browser and in an
Android browser, and the download fallback also works on Android even where
`<a download>` with a Blob is refused, because the JSON is shown in a textarea
for manual copy.
"""

from __future__ import annotations

import html
import json
from pathlib import Path

from .schema import Dataset, EvalCase, ReferenceStatus

#: Statuses a reviewer can choose from in the UI. `pending_human_review` is
#: reachable only by leaving a case alone; the UI does not offer it as a choice,
#: because "un-reviewing" a reviewed case should be a deliberate dataset edit,
#: not a stray click.
_SELECTABLE = [
    ReferenceStatus.REVIEWED,
    ReferenceStatus.DISPUTED,
    ReferenceStatus.REJECTED,
]

_CSS = """
:root { color-scheme: light dark; }
body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; margin: 0; padding: 16px;
       max-width: 1100px; margin-inline: auto; line-height: 1.5; }
h1 { font-size: 1.35rem; margin: 0 0 4px; }
.sub { opacity: .75; font-size: .85rem; margin-bottom: 16px; }
.bar { position: sticky; top: 0; z-index: 10; background: Canvas; padding: 10px 0;
       border-bottom: 1px solid color-mix(in srgb, CanvasText 20%, transparent);
       display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.bar input, .bar select, .bar button { font: inherit; padding: 6px 8px; }
.bar button { cursor: pointer; }
.card { border: 1px solid color-mix(in srgb, CanvasText 18%, transparent);
        border-radius: 10px; padding: 12px; margin: 12px 0; }
.card.approved { border-color: #2e7d32; }
.card.disputed, .card.rejected { border-color: #c62828; }
.meta { font-size: .78rem; opacity: .75; display: flex; gap: 12px; flex-wrap: wrap; }
.src { font-family: ui-monospace, monospace; font-size: .95rem; margin: 8px 0;
       background: color-mix(in srgb, CanvasText 6%, Canvas); padding: 8px; border-radius: 6px; }
.ctx { font-size: .82rem; opacity: .8; font-style: italic; }
label { display: block; font-size: .8rem; margin: 8px 0 2px; opacity: .85; }
textarea, input[type=text] { width: 100%; box-sizing: border-box; font: inherit; padding: 6px;
       border-radius: 6px; border: 1px solid color-mix(in srgb, CanvasText 25%, transparent); }
textarea.ar { direction: rtl; text-align: right; font-size: 1.05rem; min-height: 60px; }
.row { display: flex; gap: 12px; flex-wrap: wrap; }
.row > div { flex: 1 1 180px; }
.hint { font-size: .78rem; opacity: .7; margin-top: 4px; }
.out { width: 100%; box-sizing: border-box; min-height: 120px; font-family: ui-monospace, monospace;
       font-size: .72rem; }
.warn { color: #b26a00; font-size: .85rem; }
"""

#: The whole review client. Kept as one string with no interpolation from Python
#: beyond the embedded corpus JSON, so the file is reproducible byte-for-byte.
_JS = """
const DATA = __DATA__;
const root = document.getElementById('list');
const filterSel = document.getElementById('filter');
const search = document.getElementById('search');
const state = new Map();

function corpusIndex() { return corpus_index; }

function render() {
  const q = search.value.trim().toLowerCase();
  const f = filterSel.value;
  root.innerHTML = '';
  let shown = 0;
  for (const c of DATA.cases) {
    const st = state.get(c.case_id) || { status: '', arabic: '', notes: '' };
    if (f === 'pending' && st.status) continue;
    if (f === 'changed' && !st.status) continue;
    if (q && !(c.source_text.toLowerCase().includes(q) ||
               (c.reference_translation || '').toLowerCase().includes(q) ||
               c.case_id.toLowerCase().includes(q))) continue;
    shown++;
    root.appendChild(card(c, st));
  }
  document.getElementById('count').textContent = shown + ' shown of ' + DATA.cases.length;
}

function card(c, st) {
  const el = document.createElement('div');
  el.className = 'card ' + (st.status || '');
  el.dataset.caseId = c.case_id;

  const head = document.createElement('div');
  head.className = 'meta';
  const parts = [c.case_id, 'status: ' + c.reference_status];
  if (c.context_group_id) parts.push('context group: ' + c.context_group_id + ' #' + c.context_order);
  if (c.ocr_quality) parts.push('ocr: ' + c.ocr_quality);
  head.textContent = parts.join('  ·  ');
  el.appendChild(head);

  const src = document.createElement('div');
  src.className = 'src';
  src.textContent = c.source_text;
  el.appendChild(src);

  if (c.context_for_reviewer_only) {
    const ctx = document.createElement('div');
    ctx.className = 'ctx';
    ctx.textContent = 'Reviewer context only (never shown to the engine): ' +
                      c.context_for_reviewer_only;
    el.appendChild(ctx);
  }

  const wrap = document.createElement('div');
  wrap.className = 'row';

  const d1 = document.createElement('div');
  const l1 = document.createElement('label');
  l1.textContent = 'Arabic reference (edit to correct)';
  const ta = document.createElement('textarea');
  ta.className = 'ar';
  ta.value = st.arabic || c.reference_translation || '';
  ta.placeholder = 'leave exactly as-is if the reference is correct';
  ta.oninput = () => { st.arabic = ta.value; };
  d1.appendChild(l1); d1.appendChild(ta);
  wrap.appendChild(d1);

  const d2 = document.createElement('div');
  const l2 = document.createElement('label');
  l2.textContent = 'Decision';
  const sel = document.createElement('select');
  sel.innerHTML = '<option value="">— leave pending —</option>' +
    '<option value="reviewed">reviewed</option>' +
    '<option value="disputed">disputed</option>' +
    '<option value="rejected">rejected</option>';
  sel.value = st.status || '';
  sel.onchange = () => { st.status = sel.value; el.className = 'card ' + (st.status || ''); };
  d2.appendChild(l2); d2.appendChild(sel);

  const l3 = document.createElement('label');
  l3.textContent = 'Notes (required to dispute or reject)';
  const nt = document.createElement('input');
  nt.type = 'text';
  nt.value = st.notes || '';
  nt.oninput = () => { st.notes = nt.value; };
  d2.appendChild(l3); d2.appendChild(nt);
  wrap.appendChild(d2);

  el.appendChild(wrap);
  return el;
}

function collect() {
  const out = [];
  for (const c of DATA.cases) {
    const st = state.get(c.case_id) || {};
    // Only cases the reviewer actually acted on are emitted. Silence is not approval.
    if (!st.status) continue;
    out.push({
      case_id: c.case_id,
      reviewer: document.getElementById('reviewer').value.trim(),
      decision: st.status,
      reference_translation: (st.arabic !== undefined ? st.arabic : (c.reference_translation || '')),
      original_reference_translation: c.reference_translation,
      notes: (st.notes || '').trim()
    });
  }
  return { schema: DATA.schema_version, reviewer: document.getElementById('reviewer').value.trim(),
           decisions: out };
}

function build() {
  const payload = collect();
  const reviewer = document.getElementById('reviewer').value.trim();
  const missingNotes = payload.decisions.filter(d =>
      (d.decision === 'disputed' || d.decision === 'rejected') && !d.notes);
  const out = document.getElementById('out');
  const msg = document.getElementById('msg');
  if (!reviewer) { msg.textContent = 'Enter your name first — decisions must be attributable.'; return; }
  if (missingNotes.length) {
    msg.textContent = 'Notes are required for disputed/rejected: ' + missingNotes.map(d => d.case_id).join(', ');
    return;
  }
  msg.textContent = '';
  const text = JSON.stringify(payload, null, 2);
  out.value = text;
  out.style.display = 'block';
  const blob = new Blob([text], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = 'review-decisions.json';
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 4000);
}

filterSel.onchange = render;
search.oninput = render;
document.getElementById('build').onclick = build;
render();
"""


def _case_card_data(case: EvalCase) -> dict:
    return {
        "case_id": case.case_id,
        "source_text": case.source_text,
        "reference_translation": case.reference_translation,
        "reference_status": case.reference_status.value,
        "context_group_id": case.context_group_id,
        "context_order": case.context_order,
        "context_for_reviewer_only": case.context_for_reviewer_only,
        "ocr_quality": case.ocr_quality.value,
        "semantic_category": case.semantic_category.value,
    }


def build_review_html(dataset: Dataset) -> str:
    """Render the offline review sheet for a dataset."""
    payload = {
        "schema_version": dataset.schema_version,
        "dataset_version": dataset.dataset_version,
        "fingerprint": dataset.fingerprint or dataset.compute_fingerprint(),
        "cases": [_case_card_data(c) for c in dataset.cases],
    }
    # Embedding JSON inside a <script> block requires escaping the one sequence
    # that can terminate it early.
    embedded = json.dumps(payload, ensure_ascii=False).replace("</", "<\\/")

    title = f"ManhwaLens reference review — {len(dataset.cases)} cases"
    return f"""<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<style>{_CSS}</style>
<body>
<h1>{html.escape(title)}</h1>
<p class="sub">
  Dataset fingerprint <code>{html.escape(payload['fingerprint'][:32])}…</code>.
  Nothing here is approved automatically: a case you do not touch stays
  <code>pending_human_review</code>. The tool never invents a reviewer and never
  writes a timestamp — the importer stamps the time you save.
</p>
<div class="bar">
  <label style="margin:0">Your name
    <input type="text" id="reviewer" placeholder="required" autocomplete="off">
  </label>
  <select id="filter">
    <option value="all">all cases</option>
    <option value="pending">not yet decided</option>
    <option value="changed">decided</option>
  </select>
  <input type="text" id="search" placeholder="search source or Arabic…">
  <button id="build" type="button">Save decisions</button>
  <span id="count" class="hint"></span>
</div>
<p id="msg" class="warn"></p>
<div id="list"></div>
<p class="hint">
  Saving downloads <code>review-decisions.json</code>. If the download is blocked
  (common on Android), the JSON appears below — copy it into a file yourself.
</p>
<textarea id="out" class="out" style="display:none" readonly></textarea>
<script>
{_JS.replace('__DATA__', embedded).replace('function corpusIndex() { return corpus_index; }', '')}
</script>
</body>
</html>
"""


def write_review_html(dataset: Dataset, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(build_review_html(dataset), encoding="utf-8")
    return path


def parse_decisions(raw: dict) -> tuple[str, list[dict]]:
    """Validate a decisions document returned by the review sheet.

    Rejections here are deliberate and strict: a malformed review document must
    not be able to mark references reviewed.
    """
    if not isinstance(raw, dict):
        raise ValueError("decisions document must be an object")
    reviewer = (raw.get("reviewer") or "").strip()
    if not reviewer:
        raise ValueError("decisions document requires a reviewer name")
    entries = raw.get("decisions")
    if not isinstance(entries, list):
        raise ValueError("decisions document requires a 'decisions' array")

    cleaned: list[dict] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise ValueError(f"decisions[{index}] must be an object")
        case_id = (entry.get("case_id") or "").strip()
        if not case_id:
            raise ValueError(f"decisions[{index}] requires case_id")
        decision = (entry.get("decision") or "").strip()
        if decision not in {
            ReferenceStatus.REVIEWED.value,
            ReferenceStatus.DISPUTED.value,
            ReferenceStatus.REJECTED.value,
        }:
            raise ValueError(f"decisions[{index}] has unsupported decision {decision!r}")
        notes = (entry.get("notes") or "").strip()
        if decision in (ReferenceStatus.DISPUTED.value, ReferenceStatus.REJECTED.value) and not notes:
            raise ValueError(f"decisions[{index}] ({case_id}) requires notes to {decision}")
        cleaned.append(
            {
                "case_id": case_id,
                "decision": decision,
                "reference_translation": entry.get("reference_translation"),
                "notes": notes or None,
                "reviewer": (entry.get("reviewer") or reviewer).strip(),
            }
        )
    return reviewer, cleaned


# ---------------------------------------------------------------------------
# Applying decisions to a corpus
# ---------------------------------------------------------------------------


def apply_decisions(
    dataset: Dataset,
    decisions: list[dict],
    reviewer: str,
    timestamp: str,
    allow_override: bool = False,
) -> tuple[Dataset, list[str]]:
    """Return a corpus with the reviewer's explicit decisions applied.

    Rules, all of which exist so a review cannot be forged or silently widened:

    * Only cases named in ``decisions`` change. Everything else stays pending.
    * A decision of ``reviewed`` requires a non-blank reference after the edit.
    * ``disputed`` and ``rejected`` keep the reference text — a disagreement is a
      recorded finding, not a deletion.
    * ``timestamp`` is supplied by the caller as the moment the human saved. This
      function does not read a clock, so the mutation is reproducible and the
      time is never invented at import time.
    * Re-reviewing an already-reviewed case requires ``allow_override``, so a
      second pass cannot quietly overwrite a previous reviewer's decision.
    """
    by_id = dataset.by_id()
    applied: list[str] = []
    skipped: list[str] = []

    for entry in decisions:
        case_id = entry["case_id"]
        case = by_id.get(case_id)
        if case is None:
            raise ValueError(f"decision references unknown case {case_id!r}")

        decision = entry["decision"]
        if case.reference_status is ReferenceStatus.REVIEWED and not allow_override:
            skipped.append(case_id)
            continue

        new_reference = entry.get("reference_translation")
        if new_reference is None:
            new_reference = case.reference_translation

        if decision == ReferenceStatus.REVIEWED.value:
            if not (new_reference or "").strip():
                raise ValueError(
                    f"{case_id}: cannot mark reviewed with a blank Arabic reference"
                )
        else:
            # Rejected/disputed must retain the text being disagreed with.
            if not (new_reference or "").strip() and not (case.reference_translation or "").strip():
                raise ValueError(
                    f"{case_id}: {decision} requires a reference to disagree with"
                )

        case.reference_translation = new_reference
        case.reference_status = ReferenceStatus(decision)
        case.review = type(case.review)(
            reviewer=entry.get("reviewer") or reviewer,
            timestamp=timestamp,
            notes=entry.get("notes"),
        )
        applied.append(case_id)

    if applied:
        dataset.fingerprint = dataset.compute_fingerprint()
    return dataset, skipped
