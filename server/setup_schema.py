"""
Run this once to create all required tables in Supabase.

Usage:
    py setup_schema.py <postgres_url>

The Postgres URL can be found in:
  Supabase Dashboard → Project Settings → Database → Connection string → Python (psycopg2)
  It looks like: postgresql://postgres.lzwvyqnqyaacyygfnolx:<PASSWORD>@aws-0-us-east-1.pooler.supabase.com:5432/postgres

Or set the SUPABASE_DB_URL environment variable before running:
    $env:SUPABASE_DB_URL = "postgresql://..."
    py setup_schema.py
"""
import os
import sys
from pathlib import Path

def main():
    db_url = None

    if len(sys.argv) > 1:
        db_url = sys.argv[1].strip()
    else:
        db_url = os.environ.get("SUPABASE_DB_URL") or os.environ.get("DATABASE_URL")

    if not db_url:
        print("\nERROR: No database URL provided.")
        print(__doc__)
        sys.exit(1)

    try:
        import psycopg2
    except ImportError:
        print("Installing psycopg2-binary...")
        import subprocess
        subprocess.check_call([sys.executable, "-m", "pip", "install", "psycopg2-binary", "-q"])
        import psycopg2

    schema_path = Path(__file__).parent / "supabase_schema.sql"
    if not schema_path.exists():
        print(f"ERROR: Schema file not found at {schema_path}")
        sys.exit(1)

    sql = schema_path.read_text(encoding="utf-8")

    print(f"Connecting to database...")
    conn = psycopg2.connect(db_url)
    conn.autocommit = True
    cursor = conn.cursor()

    print("Running schema...")
    try:
        cursor.execute(sql)
        print("\nSchema created successfully!")
        print("Tables: companies, transactions, invoices, notifications")
    except Exception as e:
        print(f"\nERROR: {e}")
        sys.exit(1)
    finally:
        cursor.close()
        conn.close()

    print("\nYou can now start the server with:")
    print("  py -m uvicorn main:app --host 0.0.0.0 --port 8000")


if __name__ == "__main__":
    main()
