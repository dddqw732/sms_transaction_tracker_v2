from __future__ import annotations

import os
import re
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

import psycopg2
from psycopg2.extras import RealDictCursor

DB_URL_ENV_KEYS = ("SUPABASE_DB_URL", "DATABASE_URL")

# SQLite local DB path (next to this file)
SQLITE_PATH = Path(__file__).parent / "transactions.db"


# ─── Backend detection ────────────────────────────────────────────────────────

def _get_db_url() -> Optional[str]:
    for key in DB_URL_ENV_KEYS:
        value = os.environ.get(key, "").strip()
        if value:
            return value
    return None


def using_postgres() -> bool:
    return bool(_get_db_url())


def using_sqlite() -> bool:
    return not using_postgres()


# ─── Postgres helpers ─────────────────────────────────────────────────────────

def _connect():
    db_url = _get_db_url()
    if not db_url:
        raise RuntimeError("No Postgres URL configured")
    return psycopg2.connect(db_url, cursor_factory=RealDictCursor)


# ─── SQLite helpers ───────────────────────────────────────────────────────────

def _sqlite() -> sqlite3.Connection:
    conn = sqlite3.connect(str(SQLITE_PATH))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def _row_to_dict(row) -> dict:
    if row is None:
        return None
    return dict(row)


def _rows_to_dicts(rows) -> list[dict]:
    return [dict(r) for r in rows]


# ─── Init ─────────────────────────────────────────────────────────────────────

def init_db() -> None:
    if using_postgres():
        schema_path = Path(__file__).parent / "supabase_schema.sql"
        if not schema_path.exists():
            print("[OK] Database initialized (no schema file found).")
            return
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(schema_path.read_text(encoding="utf-8"))
            conn.commit()
        print("[OK] Database initialized.")
        return

    # SQLite: create tables locally
    with _sqlite() as conn:
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS companies (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                company_name TEXT NOT NULL,
                company_code TEXT NOT NULL UNIQUE,
                city TEXT NOT NULL,
                subscription_plan TEXT NOT NULL DEFAULT 'Starter',
                password_hash TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'active',
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            );

            CREATE TABLE IF NOT EXISTS transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                company_id INTEGER NOT NULL DEFAULT 1 REFERENCES companies(id),
                amount REAL NOT NULL,
                currency TEXT NOT NULL DEFAULT 'SLSH',
                sender TEXT NOT NULL DEFAULT '',
                sender_number TEXT,
                receiver TEXT NOT NULL DEFAULT '',
                receiver_number TEXT,
                provider TEXT NOT NULL DEFAULT '',
                transaction_id TEXT,
                timestamp TEXT NOT NULL DEFAULT (datetime('now')),
                balance REAL,
                type TEXT NOT NULL DEFAULT 'Received',
                raw_sms TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                UNIQUE(company_id, transaction_id)
            );

            CREATE TABLE IF NOT EXISTS invoices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                company_id INTEGER NOT NULL DEFAULT 1 REFERENCES companies(id),
                invoice_number TEXT NOT NULL,
                customer_phone TEXT NOT NULL,
                amount REAL NOT NULL,
                currency TEXT NOT NULL DEFAULT 'SLSH',
                description TEXT,
                status TEXT NOT NULL DEFAULT 'pending',
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                paid_at TEXT,
                paid_transaction_id INTEGER
            );

            CREATE TABLE IF NOT EXISTS notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                company_id INTEGER NOT NULL DEFAULT 1 REFERENCES companies(id),
                transaction_id INTEGER NOT NULL REFERENCES transactions(id),
                read INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                UNIQUE(company_id, transaction_id)
            );
        """)

        # Migration check for existing SQLite tables created prior to multi-tenant schema
        cursor = conn.cursor()
        cursor.execute("PRAGMA table_info(transactions)")
        tx_cols = [col[1] for col in cursor.fetchall()]
        if tx_cols and "company_id" not in tx_cols:
            conn.execute("ALTER TABLE transactions ADD COLUMN company_id INTEGER NOT NULL DEFAULT 1")

        cursor.execute("PRAGMA table_info(invoices)")
        inv_cols = [col[1] for col in cursor.fetchall()]
        if inv_cols and "company_id" not in inv_cols:
            conn.execute("ALTER TABLE invoices ADD COLUMN company_id INTEGER NOT NULL DEFAULT 1")

        cursor.execute("PRAGMA table_info(notifications)")
        notif_cols = [col[1] for col in cursor.fetchall()]
        if notif_cols and "company_id" not in notif_cols:
            conn.execute("ALTER TABLE notifications ADD COLUMN company_id INTEGER NOT NULL DEFAULT 1")

    print("[OK] Database initialized (SQLite local mode).")


# ─── Companies ────────────────────────────────────────────────────────────────

def get_company_by_code(company_code: str) -> Optional[dict[str, Any]]:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    select id, company_name, company_code, city,
                           subscription_plan, password_hash, status, created_at
                    from companies
                    where company_code = %s
                    """,
                    (company_code,),
                )
                row = cur.fetchone()
                return dict(row) if row else None

    with _sqlite() as conn:
        row = conn.execute(
            "SELECT * FROM companies WHERE company_code = ?", (company_code,)
        ).fetchone()
        return _row_to_dict(row)


def get_company_by_id(company_id: int) -> Optional[dict[str, Any]]:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    select id, company_name, company_code, city,
                           subscription_plan, password_hash, status, created_at
                    from companies
                    where id = %s
                    """,
                    (company_id,),
                )
                row = cur.fetchone()
                return dict(row) if row else None

    with _sqlite() as conn:
        row = conn.execute(
            "SELECT * FROM companies WHERE id = ?", (company_id,)
        ).fetchone()
        return _row_to_dict(row)


def list_companies_with_telegram_credentials() -> list[dict[str, Any]]:
    """Legacy function – returns all active companies (telegram no longer required)."""
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    select id, company_name, company_code, city,
                           subscription_plan, password_hash, status, created_at
                    from companies
                    where status = 'active'
                    order by created_at asc
                    """
                )
                return [dict(row) for row in cur.fetchall()]

    with _sqlite() as conn:
        rows = conn.execute(
            "SELECT * FROM companies WHERE status = 'active' ORDER BY created_at ASC"
        ).fetchall()
        return _rows_to_dicts(rows)


def create_company(
    company_name: str,
    company_code: str,
    city: str,
    subscription_plan: str,
    password_hash: str,
    status: str,
    telegram_api_id: Optional[str] = None,
    telegram_api_hash: Optional[str] = None,
) -> dict[str, Any]:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    insert into companies
                        (company_name, company_code, city, subscription_plan, password_hash, status)
                    values (%s, %s, %s, %s, %s, %s)
                    returning id, company_name, company_code, city, subscription_plan, status, created_at
                    """,
                    (company_name, company_code, city, subscription_plan, password_hash, status),
                )
                row = cur.fetchone()
            conn.commit()
            return dict(row)

    with _sqlite() as conn:
        cur = conn.execute(
            """
            INSERT INTO companies (company_name, company_code, city, subscription_plan, password_hash, status)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (company_name, company_code, city, subscription_plan, password_hash, status),
        )
        conn.commit()
        row = conn.execute(
            "SELECT * FROM companies WHERE id = ?", (cur.lastrowid,)
        ).fetchone()
        return _row_to_dict(row)


def get_next_company_code_number(prefix: str) -> int:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    select max(cast(substring(company_code from %s) as integer)) as max_num
                    from companies
                    where company_code ~ %s
                    """,
                    (f"^{prefix}(\\d+)$", f"^{prefix}[0-9]+$"),
                )
                row = cur.fetchone()
                max_num = row["max_num"] if row and row.get("max_num") is not None else 0
                return int(max_num) + 1

    with _sqlite() as conn:
        rows = conn.execute(
            "SELECT company_code FROM companies WHERE company_code LIKE ?", (f"{prefix}%",)
        ).fetchall()
    max_num = 0
    for row in rows:
        code = row["company_code"] or ""
        match = re.match(rf"^{re.escape(prefix)}(\d+)$", code)
        if match:
            max_num = max(max_num, int(match.group(1)))
    return max_num + 1


# ─── Transactions ─────────────────────────────────────────────────────────────

def insert_transaction(
    company_id: int,
    amount: float,
    currency: str,
    sender: str,
    receiver: str,
    provider: str,
    transaction_id: Optional[str],
    timestamp: str,
    type_: str,
    raw_sms: str,
    sender_number: Optional[str] = None,
    receiver_number: Optional[str] = None,
    balance: Optional[float] = None,
) -> Optional[int]:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    insert into transactions
                        (company_id, amount, currency, sender, sender_number, receiver, receiver_number,
                         provider, transaction_id, timestamp, balance, type, raw_sms)
                    values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s::timestamptz, %s, %s, %s)
                    on conflict (company_id, transaction_id) where transaction_id is not null do nothing
                    returning id
                    """,
                    (
                        company_id, amount, currency, sender, sender_number, receiver,
                        receiver_number, provider, transaction_id, timestamp, balance, type_, raw_sms,
                    ),
                )
                row = cur.fetchone()
            conn.commit()
            return int(row["id"]) if row else None

    with _sqlite() as conn:
        # Check for duplicate
        if transaction_id:
            dup = conn.execute(
                "SELECT id FROM transactions WHERE company_id = ? AND transaction_id = ?",
                (company_id, transaction_id),
            ).fetchone()
            if dup:
                return None
        cur = conn.execute(
            """
            INSERT INTO transactions
                (company_id, amount, currency, sender, sender_number, receiver, receiver_number,
                 provider, transaction_id, timestamp, balance, type, raw_sms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                company_id, amount, currency, sender, sender_number, receiver,
                receiver_number, provider, transaction_id, timestamp, balance, type_, raw_sms,
            ),
        )
        conn.commit()
        return cur.lastrowid


def get_transactions(
    company_id: int,
    search: Optional[str] = None,
    type_: Optional[str] = None,
    provider: Optional[str] = None,
    sort_by: str = "timestamp",
    sort_order: str = "desc",
) -> list[dict[str, Any]]:
    allowed_sort = {"timestamp", "amount", "provider", "sender", "receiver"}
    sort_by = sort_by if sort_by in allowed_sort else "timestamp"

    if using_postgres():
        order = "DESC" if sort_order.lower() == "desc" else "ASC"
        query = (
            "select id, amount, currency, sender, sender_number, receiver, receiver_number, provider, "
            "transaction_id, timestamp, balance, type, raw_sms "
            "from transactions where company_id = %s"
        )
        params: list[Any] = [company_id]

        if search:
            query += " and (sender ilike %s or receiver ilike %s or coalesce(transaction_id,'') ilike %s or provider ilike %s)"
            search_like = f"%{search}%"
            params.extend([search_like, search_like, search_like, search_like])

        if type_:
            query += " and type = %s"
            params.append(type_)

        if provider:
            query += " and provider = %s"
            params.append(provider)

        query += f" order by {sort_by} {order}"

        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(query, tuple(params))
                return [dict(row) for row in cur.fetchall()]

    # SQLite
    order = "DESC" if sort_order.lower() == "desc" else "ASC"
    query = (
        "SELECT id, amount, currency, sender, sender_number, receiver, receiver_number, "
        "provider, transaction_id, timestamp, balance, type, raw_sms "
        "FROM transactions WHERE company_id = ?"
    )
    params = [company_id]

    if search:
        s = f"%{search}%"
        query += " AND (sender LIKE ? OR receiver LIKE ? OR COALESCE(transaction_id,'') LIKE ? OR provider LIKE ?)"
        params.extend([s, s, s, s])

    if type_:
        query += " AND type = ?"
        params.append(type_)

    if provider:
        query += " AND provider = ?"
        params.append(provider)

    query += f" ORDER BY {sort_by} {order}"

    with _sqlite() as conn:
        rows = conn.execute(query, params).fetchall()
        return _rows_to_dicts(rows)


def delete_all_transactions(company_id: int) -> int:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute("delete from transactions where company_id = %s", (company_id,))
                deleted = cur.rowcount
            conn.commit()
            return deleted

    with _sqlite() as conn:
        cur = conn.execute("DELETE FROM transactions WHERE company_id = ?", (company_id,))
        conn.commit()
        return cur.rowcount


# ─── Invoices ─────────────────────────────────────────────────────────────────

def create_invoice(
    company_id: int,
    invoice_number: str,
    customer_phone: str,
    amount: float,
    currency: str,
    description: Optional[str] = None,
) -> int:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    insert into invoices
                        (company_id, invoice_number, customer_phone, amount, currency, description, status)
                    values (%s, %s, %s, %s, %s, %s, 'pending')
                    returning id
                    """,
                    (company_id, invoice_number, customer_phone, amount, currency, description),
                )
                row = cur.fetchone()
            conn.commit()
            return int(row["id"])

    with _sqlite() as conn:
        cur = conn.execute(
            """
            INSERT INTO invoices (company_id, invoice_number, customer_phone, amount, currency, description, status)
            VALUES (?, ?, ?, ?, ?, ?, 'pending')
            """,
            (company_id, invoice_number, customer_phone, amount, currency, description),
        )
        conn.commit()
        return cur.lastrowid


def get_invoices(company_id: int) -> list[dict[str, Any]]:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    select id, invoice_number, customer_phone, amount, currency, created_at,
                           paid_at, status, description, paid_transaction_id
                    from invoices
                    where company_id = %s
                    order by created_at desc
                    """,
                    (company_id,),
                )
                return [dict(row) for row in cur.fetchall()]

    with _sqlite() as conn:
        rows = conn.execute(
            """
            SELECT id, invoice_number, customer_phone, amount, currency, created_at,
                   paid_at, status, description, paid_transaction_id
            FROM invoices WHERE company_id = ? ORDER BY created_at DESC
            """,
            (company_id,),
        ).fetchall()
        return _rows_to_dicts(rows)


def update_invoice_status(company_id: int, invoice_id: int, status: str, transaction_id: Optional[int] = None) -> None:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                if status == "paid":
                    cur.execute(
                        """
                        update invoices
                        set status = %s, paid_at = now(), paid_transaction_id = %s
                        where id = %s and company_id = %s
                        """,
                        (status, transaction_id, invoice_id, company_id),
                    )
                else:
                    cur.execute(
                        "update invoices set status = %s where id = %s and company_id = %s",
                        (status, invoice_id, company_id),
                    )
            conn.commit()
            return

    with _sqlite() as conn:
        if status == "paid":
            conn.execute(
                "UPDATE invoices SET status = ?, paid_at = datetime('now'), paid_transaction_id = ? WHERE id = ? AND company_id = ?",
                (status, transaction_id, invoice_id, company_id),
            )
        else:
            conn.execute(
                "UPDATE invoices SET status = ? WHERE id = ? AND company_id = ?",
                (status, invoice_id, company_id),
            )
        conn.commit()


def find_matching_invoice(company_id: int, customer_phone: str, amount: float) -> Optional[dict[str, Any]]:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    select id, invoice_number, customer_phone, amount, currency, created_at,
                           paid_at, status, description, paid_transaction_id
                    from invoices
                    where company_id = %s and customer_phone = %s and amount = %s and status = 'pending'
                    order by created_at asc
                    limit 1
                    """,
                    (company_id, customer_phone, amount),
                )
                row = cur.fetchone()
                return dict(row) if row else None

    with _sqlite() as conn:
        row = conn.execute(
            """
            SELECT id, invoice_number, customer_phone, amount, currency, created_at,
                   paid_at, status, description, paid_transaction_id
            FROM invoices
            WHERE company_id = ? AND customer_phone = ? AND amount = ? AND status = 'pending'
            ORDER BY created_at ASC LIMIT 1
            """,
            (company_id, customer_phone, amount),
        ).fetchone()
        return _row_to_dict(row)


def delete_invoice(company_id: int, invoice_id: int) -> None:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute("delete from invoices where id = %s and company_id = %s", (invoice_id, company_id))
            conn.commit()
            return

    with _sqlite() as conn:
        conn.execute("DELETE FROM invoices WHERE id = ? AND company_id = ?", (invoice_id, company_id))
        conn.commit()


# ─── Notifications ────────────────────────────────────────────────────────────

def create_notification(company_id: int, transaction_id: int) -> None:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    insert into notifications (company_id, transaction_id)
                    values (%s, %s)
                    on conflict (company_id, transaction_id) do nothing
                    """,
                    (company_id, transaction_id),
                )
            conn.commit()
            return

    with _sqlite() as conn:
        conn.execute(
            "INSERT OR IGNORE INTO notifications (company_id, transaction_id) VALUES (?, ?)",
            (company_id, transaction_id),
        )
        conn.commit()


def get_unread_notifications(company_id: int) -> list[dict[str, Any]]:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    select id, transaction_id, created_at, read
                    from notifications
                    where company_id = %s and read = false
                    order by created_at desc
                    """,
                    (company_id,),
                )
                return [dict(row) for row in cur.fetchall()]

    with _sqlite() as conn:
        rows = conn.execute(
            "SELECT id, transaction_id, created_at, read FROM notifications WHERE company_id = ? AND read = 0 ORDER BY created_at DESC",
            (company_id,),
        ).fetchall()
        return _rows_to_dicts(rows)


def mark_notification_as_read(company_id: int, notification_id: int) -> None:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "update notifications set read = true where id = %s and company_id = %s",
                    (notification_id, company_id),
                )
            conn.commit()
            return

    with _sqlite() as conn:
        conn.execute(
            "UPDATE notifications SET read = 1 WHERE id = ? AND company_id = ?",
            (notification_id, company_id),
        )
        conn.commit()


def delete_all_notifications(company_id: int) -> None:
    if using_postgres():
        with _connect() as conn:
            with conn.cursor() as cur:
                cur.execute("delete from notifications where company_id = %s", (company_id,))
            conn.commit()
            return

    with _sqlite() as conn:
        conn.execute("DELETE FROM notifications WHERE company_id = ?", (company_id,))
        conn.commit()
