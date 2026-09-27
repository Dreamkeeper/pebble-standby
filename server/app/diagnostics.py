"""Diagnostics bundles the phone chose to send (spec: diagnostics-sharing).

The server never pulls: it can queue a *request* (see ui.py), and the
phone uploads only after the wearer taps Send. Bundles are zip files
written by the companion (already redacted there), stored per wearer
under ``CM_DATA_DIR/diagnostics/<wearer>/<id>.zip`` and deleted after
``CM_DIAG_RETENTION_DAYS``. Only admins can download or delete them.
"""
from __future__ import annotations

import os
import re
import secrets
import time

from fastapi import APIRouter, Header, HTTPException, Request

from .store import db
from .wearers import require_wearer

router = APIRouter(prefix="/api/v1")

MAX_BYTES = int(os.environ.get("CM_DIAG_MAX_BYTES", str(20 * 1024 * 1024)))
RETENTION_DAYS = int(os.environ.get("CM_DIAG_RETENTION_DAYS", "30"))
REQUEST_RANGES = (1, 3, 7, 14)

_SAFE_ID = re.compile(r"[A-Za-z0-9_-]{1,64}")
_ZIP_MAGIC = b"PK\x03\x04"
_last_purge_t = 0.0


def _base_dir() -> str:
    return os.path.join(os.environ.get("CM_DATA_DIR", "/srv/data"), "diagnostics")


def bundle_path(wearer_id: str, diag_id: str) -> str:
    if not _SAFE_ID.fullmatch(wearer_id) or not _SAFE_ID.fullmatch(diag_id):
        raise HTTPException(404, "unknown diagnostics bundle")
    return os.path.join(_base_dir(), wearer_id, f"{diag_id}.zip")


def normalize_days(days: int) -> int:
    return next((r for r in REQUEST_RANGES if r >= days), REQUEST_RANGES[-1])


def delete_bundle(wearer_id: str, diag_id: str) -> bool:
    removed = db.delete_diagnostics(wearer_id, diag_id)
    try:
        os.remove(bundle_path(wearer_id, diag_id))
    except FileNotFoundError:
        pass
    return removed


def purge_expired(now: float | None = None) -> int:
    """Delete bundles older than the retention period, file and row."""
    now = time.time() if now is None else now
    n = 0
    for row in db.diagnostics_older_than(now - RETENTION_DAYS * 86400):
        delete_bundle(row["wearer_id"], row["id"])
        db.add_event(row["wearer_id"], "diagnostics_expired", {"id": row["id"]})
        n += 1
    return n


def purge_if_due(now: float) -> None:
    """Called from the pump loop; runs the purge at most hourly."""
    global _last_purge_t
    if now - _last_purge_t >= 3600:
        _last_purge_t = now
        purge_expired(now)


@router.post("/diagnostics")
async def upload(request: Request, days: int = 7, request_id: str | None = None,
                 authorization: str | None = Header(default=None)):
    auth = require_wearer(authorization)
    wid = auth.wearer_id
    if request_id is not None and not _SAFE_ID.fullmatch(request_id):
        raise HTTPException(422, "bad request id")
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > MAX_BYTES:
        raise HTTPException(413, "diagnostics bundle too large")

    diag_id = f"diag-{int(time.time())}-{secrets.token_hex(4)}"
    final = bundle_path(wid, diag_id)
    os.makedirs(os.path.dirname(final), exist_ok=True)
    tmp = final + ".part"
    size = 0
    head = b""
    try:
        with open(tmp, "wb") as fh:
            async for chunk in request.stream():
                if not chunk:
                    continue
                size += len(chunk)
                if size > MAX_BYTES:
                    raise HTTPException(413, "diagnostics bundle too large")
                if len(head) < 4:
                    head = (head + chunk)[:4]
                fh.write(chunk)
        if size == 0 or head != _ZIP_MAGIC:
            raise HTTPException(415, "a zip archive is required")
        os.replace(tmp, final)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)

    days = max(1, min(int(days), 31))
    db.add_diagnostics(wid, diag_id, size, days, request_id)
    fulfilled = bool(request_id) and db.resolve_diag_request(
        wid, request_id, "fulfilled", diagnostics_id=diag_id)
    db.add_event(wid, "diagnostics_uploaded",
                 {"id": diag_id, "size": size, "days": days,
                  "request_id": request_id, "fulfilled": fulfilled})
    return {"id": diag_id, "size": size}


@router.post("/diagnostics/requests/{req_id}/decline")
def decline(req_id: str, authorization: str | None = Header(default=None)):
    """Always 200 for a well-formed id, so a phone whose request was
    superseded or already answered can clear it instead of retrying."""
    auth = require_wearer(authorization)
    if not _SAFE_ID.fullmatch(req_id):
        raise HTTPException(422, "bad request id")
    changed = db.resolve_diag_request(auth.wearer_id, req_id, "declined")
    if changed:
        db.add_event(auth.wearer_id, "diagnostics_declined", {"request_id": req_id})
    row = db.get_diag_request(auth.wearer_id, req_id)
    return {"state": row["state"] if row else "unknown"}
