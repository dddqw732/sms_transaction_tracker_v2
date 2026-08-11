# SMS Transaction Tracker

A multi-tenant transaction tracking system with a landing page, company signup/login (Company Code + Password), and isolated company dashboards.

---

## Project Structure

```
sms_transaction_tracker/
├── app/          → Android (Kotlin / Jetpack Compose) application
└── server/       → Python FastAPI backend + Web dashboard
    ├── main.py          → FastAPI routes, WebSocket manager
    ├── database.py      → SQLite helpers
    ├── requirements.txt → Python dependencies
    └── static/
        ├── index.html   → Dashboard HTML
        ├── style.css    → Premium styling
        └── app.js       → Dashboard JS (WebSocket, REST, filtering)
```

---

## Quick Start (Supabase + FastAPI)

### 1. Create Supabase Schema

Run the SQL from `server/supabase_schema.sql` in Supabase SQL Editor.

### 2. Configure Environment

Set environment variables before starting server:

```powershell
$env:SUPABASE_DB_URL = "postgresql://postgres:<password>@<host>:5432/postgres?sslmode=require"
$env:JWT_SECRET = "change-this-secret"
```

Optional for Telegram ingestion to a specific company:

```powershell
$env:TELETHON_COMPANY_CODE = "BAR001"
```

### 3. Start the Backend Server

```powershell
cd server
python -m pip install -r requirements.txt
python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

The landing page is available at **http://localhost:8000**.

### 4. Use Auth Flow

1. Open landing page
2. Create company with required fields:
    - Company Name
    - City
    - Telegram API credentials (optional)
    - Initial subscription plan
    - Password
    - Status
3. Copy generated unique Company Code
4. Login using Company Code + Password
5. User is redirected to their company-only dashboard

## Android App Note

```powershell
Android code currently still targets legacy unauthenticated endpoints and is out of scope for this pass.
Web/server multi-tenant auth is now the primary path.
```

## Security Notes

---

- Users do not log in with email.
- Login is strictly `company_code + password`.
- Suspended companies are blocked from login and API access.
- All dashboard queries are scoped by authenticated company.
