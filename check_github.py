import os, requests, base64

token = os.environ.get("GITHUB_TOKEN", "")
owner = os.environ.get("GITHUB_USERNAME", "dddqw732")
repo = "sms_transaction_tracker"

files_to_check = [
    "vercel.json",
    "server/main.py",
    "app/gradle.properties",
]

for path in files_to_check:
    r = requests.get(
        f"https://api.github.com/repos/{owner}/{repo}/contents/{path}",
        headers={"Authorization": f"token {token}"}
    )
    if r.status_code == 200:
        content = base64.b64decode(r.json()["content"]).decode()
        print(f"\n=== {path} ({len(content)} bytes) ===")
        # Print first 500 chars
        print(content[:500])
    else:
        print(f"\n=== {path} === FAILED: {r.status_code}")