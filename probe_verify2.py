import io, sys, json, urllib.request
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
ADMIN = "http://127.0.0.1:8788"
COMP = "http://127.0.0.1:8568"
TARGET = "01a16128-7f36-7651-99df-fb88cce0679a"

def req(url, method="GET", payload=None, token=None):
    data = json.dumps(payload).encode() if payload is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")

code, body = req(ADMIN + "/api/admin/companion", "POST", {"action": "new_code"})
pair_code = json.loads(body)["pairing"]["code"]
code, body = req(COMP + "/api/companion/pair", "POST", {"code": pair_code})
pair = json.loads(body)
token, phone_id = pair["token"], pair["phone_id"]

cursor = ""
found = None
total = 0
for page in range(10):
    q = "/api/companion/threads?limit=40" + (f"&cursor={cursor}" if cursor else "")
    code, body = req(COMP + q, token=token)
    data = json.loads(body)
    rows = data.get("threads", [])
    total += len(rows)
    for t in rows:
        if t.get("thread_id") == TARGET:
            found = t
            break
    if found:
        break
    cursor = str(data.get("next_cursor") or "")
    if not cursor:
        break
print("total scanned:", total, "| in-list:", json.dumps(found, ensure_ascii=False)[:260] if found else "NOT FOUND")
req(ADMIN + "/api/admin/companion", "POST", {"action": "remove_phone", "phone_id": phone_id})
