import os
import sys
from dotenv import load_dotenv

# Load from the server/.env file
load_dotenv('server/.env')

supabase_url = os.environ.get("SUPABASE_URL")
supabase_key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY")

if not supabase_url or not supabase_key:
    print("Error: SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY is not set.")
    sys.exit(1)

print(f"Testing Supabase REST connection to: {supabase_url}")

try:
    from supabase import create_client
    client = create_client(supabase_url, supabase_key)
    
    # Try a simple query to see if connection and auth work
    response = client.table("companies").select("id").limit(1).execute()
    print("Successfully connected to Supabase via REST API!")
    print(f"Query test successful, data returned: {response.data}")
except Exception as e:
    print(f"Failed to connect to Supabase. Error: {e}")
    sys.exit(1)
