import io, sys, json, urllib.request
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
ADMIN = "http://127.0.0.1:8788"
COMP = "http://127.0.0.1:8568"

def req(url, method="GET", payload=None, token=None):
    data = json.dumps(payload).encode() if payload is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=90) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")

code, body = req(ADMIN + "/api/admin/status")
print("bridge:", body)
code, body = req(ADMIN + "/api/admin/companion", "POST", {"action": "new_code"})
pair_code = json.loads(body)["pairing"]["code"]
code, body = req(COMP + "/api/companion/pair", "POST", {"code": pair_code})
pair = json.loads(body)
token, phone_id = pair["token"], pair["phone_id"]
code, body = req(COMP + "/api/companion/threads", "POST",
                 {"cwd": "D:\\CodexBridgeMobile", "model": "gpt-5.1-codex"}, token=token)
print("create:", code, body[:500])
# check the thread appears in the list now
code, body = req(COMP + "/api/companion/threads?limit=5", token=token)
data = json.loads(body)
first = (data.get("threads") or [{}])[0]
print("top-of-list:", first.get("thread_id"), "|", (first.get("title") or "")[:30])
req(ADMIN + "/api/admin/companion", "POST", {"action": "remove_phone", "phone_id": phone_id})
print("cleanup done")
