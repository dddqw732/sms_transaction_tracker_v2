"""
GitHub repository creator and file uploader.
Uses GitHub REST API - no git binary required.

Usage: uv run --with requests python push_to_github.py <username> <token> <repo_name>
"""
import sys
import os
import base64
import json
import time

try:
    import requests
except ImportError:
    print("Installing requests...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "requests", "-q"])
    import requests

def create_repo(username, token, repo_name, private=False):
    """Create a new GitHub repository."""
    print(f"\nCreating repository '{repo_name}' for @{username}...")
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28"
    }
    payload = {
        "name": repo_name,
        "description": "Real-time SMS financial transaction tracker — Android app + FastAPI backend + Live dashboard",
        "private": private,
        "auto_init": False
    }
    r = requests.post("https://api.github.com/user/repos", headers=headers, json=payload)
    if r.status_code == 201:
        data = r.json()
        print(f"  Repository created: {data['html_url']}")
        return data
    elif r.status_code == 422:
        print(f"  Repository '{repo_name}' already exists — using it.")
        r2 = requests.get(f"https://api.github.com/repos/{username}/{repo_name}", headers=headers)
        return r2.json()
    else:
        print(f"  ERROR creating repo: {r.status_code} - {r.text}")
        sys.exit(1)

def upload_file(username, token, repo_name, file_path, content, message="Initial commit"):
    """Upload a single file to GitHub via Contents API."""
    encoded = base64.b64encode(content).decode("utf-8")
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28"
    }
    url = f"https://api.github.com/repos/{username}/{repo_name}/contents/{file_path}"
    
    # Check if file exists already (to get SHA for update)
    sha = None
    r = requests.get(url, headers=headers)
    if r.status_code == 200:
        sha = r.json().get("sha")
    
    payload = {"message": message, "content": encoded}
    if sha:
        payload["sha"] = sha
    
    r = requests.put(url, headers=headers, json=payload)
    if r.status_code in (200, 201):
        return True
    else:
        print(f"  WARN: Failed to upload {file_path}: {r.status_code} {r.text[:100]}")
        return False

def collect_files(base_dir, skip_dirs=None, skip_extensions=None, max_size_mb=10):
    """Walk the directory and collect all relevant files."""
    skip_dirs = skip_dirs or {".git", "build", ".gradle", ".kotlin", "__pycache__", ".idea", "transactions.db"}
    skip_extensions = skip_extensions or {".apk", ".class", ".dex", ".iml", ".webp", ".zip", ".session", ".session-journal"}
    SKIP_FILES = {".env", ".env.telethon", "telethon.session", "telethon.session-journal", "transactions.db", "transactions.db-shm", "transactions.db-wal"}
    
    files = {}
    for root, dirs, filenames in os.walk(base_dir):
        # Prune skipped dirs in-place to stop recursion
        dirs[:] = [d for d in dirs if d not in skip_dirs and not d.startswith(".")]
        
        for fname in filenames:
            if fname in SKIP_FILES:
                continue
            ext = os.path.splitext(fname)[1].lower()
            if ext in skip_extensions:
                continue
            
            full_path = os.path.join(root, fname)
            rel_path = os.path.relpath(full_path, base_dir).replace("\\", "/")
            
            size_mb = os.path.getsize(full_path) / (1024 * 1024)
            if size_mb > max_size_mb:
                print(f"  Skipping large file: {rel_path} ({size_mb:.1f}MB)")
                continue
            
            try:
                with open(full_path, "rb") as f:
                    files[rel_path] = f.read()
            except Exception as e:
                print(f"  Skipping unreadable file: {rel_path} ({e})")
    
    return files

def main():
    if len(sys.argv) < 4:
        print("Usage: python push_to_github.py <username> <token> <repo_name>")
        sys.exit(1)
    
    username = sys.argv[1]
    token    = sys.argv[2]
    repo_name = sys.argv[3]
    
    project_dir = r"C:\Users\muhsin\.gemini\antigravity\scratch\sms_transaction_tracker"
    
    print("=" * 60)
    print("  GitHub Push Tool - SMS Transaction Tracker")
    print("=" * 60)
    
    # Create the repo
    repo_data = create_repo(username, token, repo_name, private=False)
    repo_url = repo_data.get("html_url", f"https://github.com/{username}/{repo_name}")
    
    # Collect all files
    print(f"\nScanning project files in: {project_dir}")
    files = collect_files(project_dir)
    print(f"  Found {len(files)} files to upload.")
    
    # Upload files
    print(f"\nUploading files to GitHub...")
    success_count = 0
    fail_count = 0
    for i, (rel_path, content) in enumerate(files.items(), 1):
        print(f"  [{i}/{len(files)}] {rel_path}", end=" ... ")
        ok = upload_file(username, token, repo_name, rel_path, content)
        if ok:
            print("OK")
            success_count += 1
        else:
            print("FAILED")
            fail_count += 1
        time.sleep(0.15)  # Rate limit courtesy delay
    
    print("\n" + "=" * 60)
    print(f"  Done! {success_count} files uploaded, {fail_count} failed.")
    print(f"  Repository URL: {repo_url}")
    print("=" * 60)

if __name__ == "__main__":
    main()
