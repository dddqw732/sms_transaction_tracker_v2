"""Push pyproject.toml to GitHub."""
import base64
import requests
import sys

def push_file(username, token, repo_name):
    """Push pyproject.toml to GitHub."""
    
    with open("pyproject.toml", 'rb') as f:
        content = f.read()
    
    encoded = base64.b64encode(content).decode("utf-8")
    
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28"
    }
    
    url = f"https://api.github.com/repos/{username}/{repo_name}/contents/pyproject.toml"
    
    # Get current file to get SHA
    print("Getting current file SHA...")
    r_get = requests.get(url, headers=headers)
    
    sha = None
    if r_get.status_code == 200:
        sha = r_get.json()['sha']
        print(f"Found existing file, SHA: {sha}")
    
    payload = {
        "message": "Fix Python version requirement (>=3.10 for FastAPI 0.139.0)",
        "content": encoded,
        "branch": "main"
    }
    
    if sha:
        payload["sha"] = sha
    
    print("Pushing pyproject.toml to GitHub...")
    r = requests.put(url, headers=headers, json=payload)
    
    if r.status_code in [201, 200]:
        print("✅ pyproject.toml updated successfully!")
        return True
    else:
        print(f"❌ Upload failed: {r.status_code}")
        print(r.text)
        return False

if __name__ == "__main__":
    push_file(sys.argv[1], sys.argv[2], sys.argv[3])
