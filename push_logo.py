"""
Push logo to GitHub using the GitHub API.
"""
import sys
import base64
import requests

def upload_logo(username, token, repo_name):
    """Upload logo.png to GitHub."""
    logo_path = "server/static/logo.png"
    
    # Read logo file
    with open(logo_path, 'rb') as f:
        logo_content = f.read()
    
    # Encode to base64
    encoded = base64.b64encode(logo_content).decode("utf-8")
    
    # GitHub API headers
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28"
    }
    
    # Upload file
    url = f"https://api.github.com/repos/{username}/{repo_name}/contents/server/static/logo.png"
    payload = {
        "message": "Add Cash-In logo",
        "content": encoded,
        "branch": "main"
    }
    
    print("Uploading Cash-In logo to GitHub...")
    r = requests.put(url, headers=headers, json=payload)
    
    if r.status_code in [201, 200]:
        print("✅ Logo uploaded successfully!")
        print(f"   File: server/static/logo.png")
        print(f"   Repository: {repo_name}")
        return True
    else:
        print(f"❌ Upload failed: {r.status_code}")
        print(f"   {r.text}")
        return False

if __name__ == "__main__":
    username = sys.argv[1]
    token = sys.argv[2]
    repo_name = sys.argv[3]
    
    upload_logo(username, token, repo_name)
