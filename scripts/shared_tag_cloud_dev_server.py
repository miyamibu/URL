#!/usr/bin/env python3
import argparse
import html
import json
import os
import secrets
import threading
import uuid
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Optional, Union
from urllib.parse import parse_qs, urlparse


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def future_iso(days: int = 7) -> str:
    return (datetime.now(timezone.utc) + timedelta(days=days)).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def random_token(prefix: str) -> str:
    return f"{prefix}_{secrets.token_urlsafe(24)}"


class Store:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.lock = threading.Lock()
        self.data = self._load()

    def _load(self) -> dict:
        if not self.path.exists():
            return {
                "users": {},
                "users_by_id": {},
                "access_tokens": {},
                "refresh_tokens": {},
                "tags": {},
                "members": {},
                "urls": {},
                "applied_ops": {},
                "invites": {},
            }
        return json.loads(self.path.read_text())

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.data, ensure_ascii=False, indent=2, sort_keys=True))

    def get_user_by_email(self, email: str):
        return self.data["users"].get(email.lower())

    def get_user_by_id(self, user_id: str):
        return self.data["users_by_id"].get(user_id)

    def upsert_user(self, email: str, password: str) -> dict:
        email = email.strip().lower()
        user = self.get_user_by_email(email)
        if user is None:
            user = {
                "id": str(uuid.uuid4()),
                "email": email,
                "password": password,
                "created_at": now_iso(),
            }
            self.data["users"][email] = user
            self.data["users_by_id"][user["id"]] = user
        else:
            user["password"] = password
        return user

    def issue_session(self, user: dict) -> dict:
        access_token = random_token("access")
        refresh_token = random_token("refresh")
        self.data["access_tokens"][access_token] = user["id"]
        self.data["refresh_tokens"][refresh_token] = user["id"]
        return {
            "access_token": access_token,
            "refresh_token": refresh_token,
            "user": {
                "id": user["id"],
                "email": user["email"],
            },
        }

    def auth_user_id(self, headers) -> Optional[str]:
        raw = headers.get("Authorization", "")
        if not raw.startswith("Bearer "):
            return None
        token = raw[len("Bearer ") :].strip()
        return self.data["access_tokens"].get(token)

    def _ensure_tag_containers(self, tag_id: str) -> None:
        self.data["members"].setdefault(tag_id, {})
        self.data["urls"].setdefault(tag_id, {})

    def _require_active_role(self, tag_id: str, user_id: str, allowed_roles: set[str]) -> None:
        member = self.data["members"].get(tag_id, {}).get(user_id)
        if member is None or member["status"] != "active" or member["role"] not in allowed_roles:
            raise ValueError("forbidden")

    def _bump_tag(self, tag_id: str) -> None:
        tag = self.data["tags"][tag_id]
        tag["version"] += 1
        tag["updated_at"] = now_iso()

    def apply_ops(self, user_id: str, payload: list[dict]) -> dict:
        results = []
        user_applied = self.data["applied_ops"].setdefault(user_id, {})
        for op in payload:
            op_id = op["op_id"]
            if op_id in user_applied:
                results.append(deepcopy(user_applied[op_id]))
                continue

            op_type = op["type"]
            tag_id = op.get("tag_id")
            result = {"op_id": op_id, "status": "applied"}

            if op_type == "create_tag":
                if tag_id not in self.data["tags"]:
                    timestamp = now_iso()
                    self.data["tags"][tag_id] = {
                        "id": tag_id,
                        "name": op["name"].strip(),
                        "created_by": user_id,
                        "created_at": timestamp,
                        "updated_at": timestamp,
                        "deleted_at": None,
                        "version": 1,
                    }
                self._ensure_tag_containers(tag_id)
                self.data["members"][tag_id][user_id] = {
                    "tag_id": tag_id,
                    "user_id": user_id,
                    "role": "owner",
                    "status": "active",
                    "created_at": self.data["tags"][tag_id]["created_at"],
                    "updated_at": now_iso(),
                }
                result["tag_id"] = tag_id

            elif op_type == "rename_tag":
                self._require_active_role(tag_id, user_id, {"owner", "editor"})
                self.data["tags"][tag_id]["name"] = op["name"].strip()
                self._bump_tag(tag_id)
                result["tag_id"] = tag_id

            elif op_type == "delete_tag":
                self._require_active_role(tag_id, user_id, {"owner"})
                self.data["tags"][tag_id]["deleted_at"] = now_iso()
                self._bump_tag(tag_id)
                result["tag_id"] = tag_id

            elif op_type == "add_url_to_tag":
                self._require_active_role(tag_id, user_id, {"owner", "editor"})
                self._ensure_tag_containers(tag_id)
                normalized_url = op["normalized_url"]
                existing = None
                for url_record in self.data["urls"][tag_id].values():
                    if url_record["normalized_url"] == normalized_url:
                        existing = url_record
                        break
                if existing is None:
                    url_id = op["url_id"]
                    timestamp = now_iso()
                    url_record = {
                        "id": url_id,
                        "tag_id": tag_id,
                        "raw_url": op["raw_url"],
                        "normalized_url": normalized_url,
                        "normalization_version": op["normalization_version"],
                        "added_by": user_id,
                        "created_at": timestamp,
                        "updated_at": timestamp,
                        "deleted_at": None,
                    }
                    self.data["urls"][tag_id][url_id] = url_record
                    existing = url_record
                else:
                    existing["deleted_at"] = None
                    existing["updated_at"] = now_iso()
                self._bump_tag(tag_id)
                result["tag_id"] = tag_id
                result["url_id"] = existing["id"]
                result["normalized_url"] = existing["normalized_url"]

            elif op_type == "remove_url_from_tag":
                self._require_active_role(tag_id, user_id, {"owner", "editor"})
                url_id = op.get("url_id")
                if url_id and url_id in self.data["urls"].get(tag_id, {}):
                    self.data["urls"][tag_id][url_id]["deleted_at"] = now_iso()
                    self.data["urls"][tag_id][url_id]["updated_at"] = now_iso()
                else:
                    normalized = op.get("normalized_url")
                    for url_record in self.data["urls"].get(tag_id, {}).values():
                        if normalized and url_record["normalized_url"] == normalized:
                            url_record["deleted_at"] = now_iso()
                            url_record["updated_at"] = now_iso()
                            url_id = url_record["id"]
                            break
                self._bump_tag(tag_id)
                result["tag_id"] = tag_id
                result["url_id"] = url_id

            elif op_type == "invite_member":
                self._require_active_role(tag_id, user_id, {"owner"})
                invited_user_id = op["user_id"]
                self._ensure_tag_containers(tag_id)
                timestamp = now_iso()
                self.data["members"][tag_id][invited_user_id] = {
                    "tag_id": tag_id,
                    "user_id": invited_user_id,
                    "role": op.get("role", "editor"),
                    "status": "invited",
                    "created_at": timestamp,
                    "updated_at": timestamp,
                }
                self._bump_tag(tag_id)
                result["tag_id"] = tag_id
                result["user_id"] = invited_user_id

            elif op_type == "change_member_role":
                self._require_active_role(tag_id, user_id, {"owner"})
                target_user_id = op["user_id"]
                member = self.data["members"].get(tag_id, {}).get(target_user_id)
                if member is None:
                    raise ValueError("not_found")
                next_role = op["role"]
                if next_role == "owner":
                    raise ValueError("use_transfer_ownership")
                if target_user_id == user_id and member["role"] == "owner":
                    raise ValueError("owner_transfer_required")
                member["role"] = next_role
                member["updated_at"] = now_iso()
                self._bump_tag(tag_id)
                result["tag_id"] = tag_id
                result["user_id"] = target_user_id

            elif op_type == "remove_member":
                target_user_id = op["user_id"]
                if target_user_id == user_id:
                    self._require_active_role(tag_id, user_id, {"owner", "editor", "viewer"})
                else:
                    self._require_active_role(tag_id, user_id, {"owner"})
                member = self.data["members"].get(tag_id, {}).get(target_user_id)
                if member is None:
                    raise ValueError("not_found")
                if target_user_id == user_id and member["role"] == "owner":
                    raise ValueError("owner_cannot_leave")
                member["status"] = "removed"
                member["updated_at"] = now_iso()
                self._bump_tag(tag_id)
                result["tag_id"] = tag_id
                result["user_id"] = target_user_id

            else:
                raise ValueError(f"unknown_op_type:{op_type}")

            user_applied[op_id] = deepcopy(result)
            results.append(result)

        self.save()
        return {"results": results}

    def pull_snapshot(self, user_id: str) -> dict:
        visible_tag_ids = []
        members = []
        urls = []
        tags = []
        for tag_id, tag in self.data["tags"].items():
            member = self.data["members"].get(tag_id, {}).get(user_id)
            if member is None or member["status"] != "active" or tag["deleted_at"] is not None:
                continue
            visible_tag_ids.append(tag_id)
            tags.append(deepcopy(tag))
            for member_record in self.data["members"].get(tag_id, {}).values():
                if member_record["status"] == "removed":
                    continue
                members.append(deepcopy(member_record))
            for url_record in self.data["urls"].get(tag_id, {}).values():
                if url_record["deleted_at"] is None:
                    urls.append(deepcopy(url_record))

        return {
            "pulled_at": now_iso(),
            "normalization_version": 1,
            "tags": tags,
            "members": members,
            "urls": urls,
        }

    def create_invite(self, user_id: str, tag_id: str, role: str) -> dict:
        self._require_active_role(tag_id, user_id, {"owner"})
        token = random_token("invite")
        invite = {
            "invite_token": token,
            "tag_id": tag_id,
            "role": role,
            "created_by": user_id,
            "expires_at": future_iso(7),
        }
        self.data["invites"][token] = invite
        self.save()
        return {
            "tag_id": tag_id,
            "invite_token": token,
            "expires_at": invite["expires_at"],
            "role": role,
        }

    def transfer_ownership(self, user_id: str, tag_id: str, new_owner_user_id: str) -> dict:
        if not new_owner_user_id or new_owner_user_id == user_id:
            raise ValueError("invalid_new_owner")
        tag = self.data["tags"].get(tag_id)
        if tag is None or tag.get("deleted_at") is not None:
            raise ValueError("not_found")
        self._require_active_role(tag_id, user_id, {"owner"})
        members = self.data["members"].get(tag_id, {})
        current_member = members.get(user_id)
        target_member = members.get(new_owner_user_id)
        if target_member is None:
            raise ValueError("target_member_not_found")
        if target_member["status"] != "active":
            raise ValueError("target_member_not_active")
        if target_member["role"] == "owner":
            raise ValueError("target_already_owner")
        timestamp = now_iso()
        current_member["role"] = "editor"
        current_member["updated_at"] = timestamp
        target_member["role"] = "owner"
        target_member["updated_at"] = timestamp
        self._bump_tag(tag_id)
        self.save()
        return {
            "tag_id": tag_id,
            "previous_owner_user_id": user_id,
            "new_owner_user_id": new_owner_user_id,
        }

    def accept_invite(self, user_id: str, invite_token: str) -> dict:
        invite = self.data["invites"].get(invite_token)
        if invite is None:
            raise ValueError("invalid_invite")
        if datetime.fromisoformat(invite["expires_at"].replace("Z", "+00:00")) < datetime.now(timezone.utc):
            raise ValueError("invalid_invite")
        tag_id = invite["tag_id"]
        self._ensure_tag_containers(tag_id)
        timestamp = now_iso()
        self.data["members"][tag_id][user_id] = {
            "tag_id": tag_id,
            "user_id": user_id,
            "role": invite["role"],
            "status": "active",
            "created_at": timestamp,
            "updated_at": timestamp,
        }
        self._bump_tag(tag_id)
        self.save()
        return {
            "tag_id": tag_id,
            "tag_name": self.data["tags"][tag_id]["name"],
            "role": invite["role"],
            "status": "active",
        }

    def preview_invite(self, invite_token: str) -> dict:
        invite = self.data["invites"].get(invite_token)
        if invite is None:
            raise ValueError("invalid_invite")
        if datetime.fromisoformat(invite["expires_at"].replace("Z", "+00:00")) < datetime.now(timezone.utc):
            raise ValueError("invalid_invite")
        tag = self.data["tags"].get(invite["tag_id"])
        if tag is None or tag.get("deleted_at") is not None:
            raise ValueError("invalid_invite")
        return {
            "tag_name": tag["name"],
        }

    def delete_account(self, user_id: str) -> None:
        owned_blockers = []
        for tag_id, tag in self.data["tags"].items():
            member = self.data["members"].get(tag_id, {}).get(user_id)
            if member is None or member["status"] != "active" or member["role"] != "owner" or tag["deleted_at"] is not None:
                continue
            active_others = [
                record
                for other_id, record in self.data["members"].get(tag_id, {}).items()
                if other_id != user_id and record["status"] == "active"
            ]
            if active_others:
                owned_blockers.append(tag_id)
        if owned_blockers:
            raise ValueError("owner_transfer_required")

        for tag_id, tag in self.data["tags"].items():
            member = self.data["members"].get(tag_id, {}).get(user_id)
            if member is None:
                continue
            if member["role"] == "owner" and member["status"] == "active":
                tag["deleted_at"] = now_iso()
                tag["updated_at"] = now_iso()
                tag["version"] += 1
            else:
                active_owner_id = next(
                    (
                        other_id
                        for other_id, record in self.data["members"].get(tag_id, {}).items()
                        if other_id != user_id and record["status"] == "active" and record["role"] == "owner"
                    ),
                    None,
                )
                if active_owner_id is not None:
                    if tag.get("created_by") == user_id:
                        tag["created_by"] = active_owner_id
                    for invite in self.data["invites"].values():
                        if invite.get("tag_id") == tag_id and invite.get("created_by") == user_id:
                            invite["created_by"] = active_owner_id
                    for url_record in self.data["urls"].get(tag_id, {}).values():
                        if url_record.get("added_by") == user_id:
                            url_record["added_by"] = active_owner_id
                member["status"] = "removed"
                member["updated_at"] = now_iso()

        user = self.data["users_by_id"].pop(user_id, None)
        if user is not None:
            self.data["users"].pop(user["email"], None)

        access_to_remove = [token for token, token_user_id in self.data["access_tokens"].items() if token_user_id == user_id]
        refresh_to_remove = [token for token, token_user_id in self.data["refresh_tokens"].items() if token_user_id == user_id]
        for token in access_to_remove:
            self.data["access_tokens"].pop(token, None)
        for token in refresh_to_remove:
            self.data["refresh_tokens"].pop(token, None)
        self.save()


class Handler(BaseHTTPRequestHandler):
    server_version = "URLSaverSharedTagDev/1.0"

    def log_message(self, format: str, *args) -> None:
        print("%s - - [%s] %s" % (self.client_address[0], self.log_date_time_string(), format % args))

    @property
    def store(self) -> Store:
        return self.server.store  # type: ignore[attr-defined]

    def _read_json(self) -> Union[dict, list]:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length > 0 else b"{}"
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def _send_json(self, status: int, payload: Union[dict, list]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_error_json(self, status: int, message: str) -> None:
        self._send_json(status, {"message": message})

    def _send_html(self, status: int, body: str) -> None:
        encoded = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        segments = [segment for segment in parsed.path.split("/") if segment]
        if len(segments) == 2 and segments[0] == "invite":
            token = segments[1]
            escaped_app_url = html.escape(f"urlsaver://invite/{token}", quote=True)
            escaped_token = html.escape(token)
            return self._send_html(
                200,
                f"""<!doctype html>
<html lang="ja">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>UrlSaver 共有タグ招待</title>
  <meta http-equiv="refresh" content="0; url={escaped_app_url}">
</head>
<body>
  <p>UrlSaver の共有タグ招待を開いています。</p>
  <p><a href="{escaped_app_url}">アプリで開く</a></p>
  <script>
    window.location.href = "{escaped_app_url}";
  </script>
  <noscript>招待トークン: {escaped_token}</noscript>
</body>
</html>""",
            )
        return self._send_error_json(404, "not_found")

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        try:
            with self.store.lock:
                if parsed.path == "/auth/v1/signup":
                    payload = self._read_json()
                    assert isinstance(payload, dict)
                    email = str(payload.get("email", "")).strip().lower()
                    password = str(payload.get("password", ""))
                    if not email or not password:
                        return self._send_error_json(400, "email_and_password_required")
                    user = self.store.get_user_by_email(email)
                    if user is not None:
                        return self._send_error_json(400, "user_already_exists")
                    user = self.store.upsert_user(email, password)
                    session = self.store.issue_session(user)
                    self.store.save()
                    return self._send_json(200, session)

                if parsed.path == "/auth/v1/token":
                    query = parse_qs(parsed.query)
                    grant_type = query.get("grant_type", [""])[0]
                    payload = self._read_json()
                    assert isinstance(payload, dict)
                    if grant_type == "password":
                        email = str(payload.get("email", "")).strip().lower()
                        password = str(payload.get("password", ""))
                        user = self.store.get_user_by_email(email)
                        if user is None or user["password"] != password:
                            return self._send_error_json(400, "invalid_login")
                        session = self.store.issue_session(user)
                        self.store.save()
                        return self._send_json(200, session)
                    if grant_type == "refresh_token":
                        refresh_token = str(payload.get("refresh_token", ""))
                        user_id = self.store.data["refresh_tokens"].get(refresh_token)
                        if user_id is None:
                            return self._send_error_json(400, "invalid_refresh_token")
                        user = self.store.get_user_by_id(user_id)
                        if user is None:
                            return self._send_error_json(400, "invalid_refresh_token")
                        session = self.store.issue_session(user)
                        self.store.save()
                        return self._send_json(200, session)
                    return self._send_error_json(400, "unsupported_grant_type")

                if parsed.path == "/rest/v1/rpc/preview_shared_tag_invite":
                    payload = self._read_json()
                    assert isinstance(payload, dict)
                    token = str(payload.get("p_token", ""))
                    return self._send_json(200, self.store.preview_invite(token))

                user_id = self.store.auth_user_id(self.headers)
                if user_id is None:
                    return self._send_error_json(401, "auth_required")

                if parsed.path == "/rest/v1/rpc/apply_shared_tag_ops":
                    payload = self._read_json()
                    if isinstance(payload, dict) and "payload" in payload:
                        payload = payload["payload"]
                    if not isinstance(payload, list):
                        return self._send_error_json(400, "payload_must_be_array")
                    response = self.store.apply_ops(user_id, payload)
                    return self._send_json(200, response)

                if parsed.path == "/rest/v1/rpc/pull_shared_tag_snapshot":
                    return self._send_json(200, self.store.pull_snapshot(user_id))

                if parsed.path == "/rest/v1/rpc/create_shared_tag_invite":
                    payload = self._read_json()
                    assert isinstance(payload, dict)
                    tag_id = str(payload.get("p_tag_id", ""))
                    role = str(payload.get("p_role", "editor"))
                    return self._send_json(200, self.store.create_invite(user_id, tag_id, role))

                if parsed.path == "/rest/v1/rpc/transfer_shared_tag_ownership":
                    payload = self._read_json()
                    assert isinstance(payload, dict)
                    tag_id = str(payload.get("p_tag_id", ""))
                    new_owner_user_id = str(payload.get("p_new_owner_user_id", ""))
                    return self._send_json(200, self.store.transfer_ownership(user_id, tag_id, new_owner_user_id))

                if parsed.path == "/rest/v1/rpc/accept_shared_tag_invite":
                    payload = self._read_json()
                    assert isinstance(payload, dict)
                    token = str(payload.get("p_token", ""))
                    return self._send_json(200, self.store.accept_invite(user_id, token))

                if parsed.path == "/rest/v1/rpc/delete_my_account":
                    self.store.delete_account(user_id)
                    return self._send_json(200, {})

                return self._send_error_json(404, "not_found")
        except ValueError as exc:
            message = str(exc)
            status = 403 if message == "forbidden" else 400
            self._send_error_json(status, message)
        except Exception as exc:  # pragma: no cover - dev server fallback
            self._send_error_json(500, str(exc))


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a local shared-tag cloud dev server.")
    parser.add_argument("--host", default=os.environ.get("URLSAVER_DEV_SERVER_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("URLSAVER_DEV_SERVER_PORT", "8787")))
    parser.add_argument(
        "--state-file",
        default=os.environ.get(
            "URLSAVER_DEV_SERVER_STATE_FILE",
            str(Path.home() / ".urlsaver" / "shared_tag_cloud_dev_state.json"),
        ),
    )
    args = parser.parse_args()

    store = Store(Path(args.state_file).expanduser())
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.store = store  # type: ignore[attr-defined]
    print(f"Shared tag cloud dev server listening on http://{args.host}:{args.port}")
    print(f"State file: {Path(args.state_file).expanduser()}")
    server.serve_forever()


if __name__ == "__main__":
    main()
