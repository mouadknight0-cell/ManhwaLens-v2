"""Device-output export/import: the bridge between a phone and the offline harness.

Two halves:

* :func:`build_device_corpus_asset` -- writes the JSON asset the on-device
  instrumentation test reads. It is generated from the same corpus the harness
  evaluates, so case ids cannot drift between the two sides.
* :func:`import_device_export` -- validates a device export and converts it into
  the harness's output-document shape, preserving every failure.

The importer is deliberately strict. A device file with an unknown case id, a
missing case, a duplicate case, or a malformed record is an error the caller must
see, because silently coercing it would misrepresent what the engine produced.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .harness import EngineOutput, HarnessError, OutputSet, parse_outputs
from .schema import Dataset, OutputStatus

#: Schema the device exporter writes and this importer accepts.
DEVICE_EXPORT_SCHEMA = "1.0.0"

#: Directory (inside the app's external files dir) the instrumentation test
#: writes to. CI already pulls this directory with `adb pull`.
DEVICE_EXPORT_DIR = "overlay-evidence"
DEVICE_EXPORT_FILE = "translation-eval-export.json"


def build_device_corpus_asset(dataset: Dataset) -> dict[str, Any]:
    """The payload the on-device test consumes.

    Only what the device needs is included: the case id and the source text. The
    Arabic reference is deliberately **excluded** so that a reference can never
    leak onto a device, into a log, or into an exported file.
    """
    return {
        "schema": DEVICE_EXPORT_SCHEMA,
        "dataset_fingerprint": dataset.fingerprint or dataset.compute_fingerprint(),
        "cases": [
            {"case_id": c.case_id, "source_text": c.source_text}
            for c in sorted(dataset.cases, key=lambda c: c.case_id)
        ],
    }


def write_device_corpus_asset(dataset: Dataset, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(build_device_corpus_asset(dataset), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return path


@dataclass
class DeviceExport:
    """A validated device export plus the problems found in it."""

    engine_id: str
    engine_revision: str
    app_version: str | None
    model_identity: str | None
    device: dict[str, Any]
    outputs: dict[str, EngineOutput]
    problems: list[str] = field(default_factory=list)
    results: list[dict[str, Any]] = field(default_factory=list)

    def as_output_document(self) -> dict[str, Any]:
        """Convert to the Phase A harness output-document shape."""
        return {
            "engine_id": self.engine_id,
            "revision": self.engine_revision,
            "config": {
                "app_version": self.app_version,
                "model_identity": self.model_identity,
                "device": self.device,
                "device_export": True,
            },
            "outputs": [
                {
                    "case_id": out.case_id,
                    "text": out.text,
                    "status": out.status.value,
                    "error_code": out.error_code,
                    "note": out.note,
                }
                for out in sorted(self.outputs.values(), key=lambda o: o.case_id)
            ],
        }

    def output_set(self) -> OutputSet:
        return parse_outputs(self.as_output_document())


def import_device_export(raw: dict[str, Any], dataset: Dataset) -> DeviceExport:
    """Validate a device export against the corpus.

    Every problem is collected rather than raised on the first one, so an operator
    can see the whole picture from a partially failed device run. Hard structural
    faults still raise, because nothing downstream can be trusted without them.
    """
    if not isinstance(raw, dict):
        raise HarnessError("device export must be a JSON object")

    schema = raw.get("schema")
    if schema != DEVICE_EXPORT_SCHEMA:
        raise HarnessError(
            f"device export schema {schema!r} is not {DEVICE_EXPORT_SCHEMA!r}"
        )

    engine = raw.get("engine") or {}
    if not isinstance(engine, dict):
        raise HarnessError("device export 'engine' must be an object")
    engine_id = (engine.get("engine_id") or "").strip()
    if not engine_id:
        raise HarnessError("device export requires engine.engine_id")

    results = raw.get("results")
    if not isinstance(results, list):
        raise HarnessError("device export requires a 'results' array")

    problems: list[str] = []
    outputs: dict[str, EngineOutput] = {}
    known = dataset.by_id()

    for index, entry in enumerate(results):
        if not isinstance(entry, dict):
            raise HarnessError(f"results[{index}] must be an object")
        case_id = (entry.get("case_id") or "").strip()
        if not case_id:
            raise HarnessError(f"results[{index}] requires case_id")
        if case_id not in known:
            problems.append(f"unknown case id in device export: {case_id}")
            continue
        if case_id in outputs:
            problems.append(f"duplicate case id in device export: {case_id}")

        text = entry.get("translated")
        if text is None:
            text = entry.get("text") or ""
        if not isinstance(text, str):
            raise HarnessError(f"results[{index}].translated must be a string")

        raw_status = (entry.get("status") or "").strip().lower()
        error_code = entry.get("error_code") or None
        if raw_status in ("ok", "translated", "success"):
            status = OutputStatus.OK if text.strip() else OutputStatus.EMPTY
        elif raw_status in ("failed", "error"):
            status = OutputStatus.FAILED
        elif raw_status in ("empty", ""):
            status = OutputStatus.EMPTY if not text.strip() else OutputStatus.OK
        else:
            status = OutputStatus.MALFORMED
            problems.append(f"{case_id}: unrecognised device status {raw_status!r}")

        outputs[case_id] = EngineOutput(
            case_id=case_id,
            text=text,
            status=status,
            error_code=str(error_code) if error_code else None,
            note=entry.get("method"),
        )

    # Missing cases are reported, never filled.
    missing = sorted(set(known) - set(outputs))
    if missing:
        problems.append(
            f"{len(missing)} of {len(known)} cases have no device output: "
            + ", ".join(missing[:10])
            + (" …" if len(missing) > 10 else "")
        )

    device = raw.get("device") or {}
    if not isinstance(device, dict):
        device = {}

    export = DeviceExport(
        engine_id=engine_id,
        engine_revision=str(engine.get("revision") or "unknown"),
        app_version=engine.get("app_version"),
        model_identity=engine.get("model_identity"),
        device=device,
        outputs=outputs,
        problems=problems,
        results=results,
    )

    if not export.engine_revision or export.engine_revision == "unknown":
        problems.append(
            "engine revision missing: the run cannot be tied to a commit, so it "
            "cannot serve as a reproducible baseline"
        )
    return export


def load_device_export(path: Path, dataset: Dataset) -> DeviceExport:
    if not path.is_file():
        raise HarnessError(f"device export not found: {path}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise HarnessError(f"device export is not valid JSON: {exc}") from exc
    return import_device_export(raw, dataset)
