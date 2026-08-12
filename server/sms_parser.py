import re
from datetime import datetime, timezone, timedelta


# ─── REGEX PATTERNS ──────────────────────────────────────────────────────────

# 1. Reference / Transaction ID (Ref:15501107102, Tix:15189318791, TrxId:998877)
REF_PATTERN = re.compile(
    r"(?:Ref|Tix|TxID|TrxId|Reference|Id)\s*:\s*([A-Za-z0-9]+)",
    re.IGNORECASE
)

# 2. Date / Timestamp (Date: 12/08/26 10:54:59 or Tar:02/07/26 22:43:20)
DATE_PATTERN = re.compile(
    r"(?:Date|Tar)\s*:\s*(\d{2}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2})",
    re.IGNORECASE
)

# 3. Balance (Hadhaagaaga:SLSH 4,000.3 or Your New A/C Balance is SLSH4,000.3 or Balance: $50)
BALANCE_PATTERN = re.compile(
    r"(?:Hadhaagaaga|Balance|A/C Balance|New Balance)\s*(?:is|:)?\s*(?:SLSH|\$)?\s*([\d,]+(?:\.\d+)?)",
    re.IGNORECASE
)

# 4. Received Patterns:
# English: SLSH2,000 Received from CABDIMUXSIN CUMAR AXMED (637803712)
ENG_RECEIVED = re.compile(
    r"(?:SLSH|\$)\s*([\d,]+(?:\.\d+)?)\s+Received\s+from\s+([^\(]+?)(?:\s*\(([^)]+)\))?(?:,Date|,|\.|$)",
    re.IGNORECASE
)

# Somali: Waxaad SLSH1,000 ka heshay CABDIMUXSIN CUMAR AXMED (637803712)
SOM_RECEIVED = re.compile(
    r"Waxaad\s+(?:SLSH|\$)?\s*([\d,]+(?:\.\d+)?)\s+ka heshay\s+([^\(]+?)(?:\s*\(([^)]+)\))?(?:,Date|,|\.|$)",
    re.IGNORECASE
)

# Generic Received: $10 ka heshay 0615551234 or Received $10 from MOHAMED
GENERIC_RECEIVED = re.compile(
    r"(?:SLSH|\$)?\s*([\d,]+(?:\.\d+)?)\s*(?:ka heshay|received from|ka socda)\s+([^\(]+?)(?:\s*\(([^)]+)\))?(?:,Date|,|\.|$)",
    re.IGNORECASE
)

# 5. Sent Patterns:
# English: SLSH2,000 Sent to CABDIMUXSIN CUMAR AXMED (637803712)
ENG_SENT = re.compile(
    r"(?:SLSH|\$)\s*([\d,]+(?:\.\d+)?)\s+Sent\s+to\s+([^\(]+?)(?:\s*\(([^)]+)\))?(?:,Date|,|\.|$)",
    re.IGNORECASE
)

# Somali: SLSH1,000 ayaad u dirtay CABDIMUXSIN CUMAR AXMED (637803712)
SOM_SENT = re.compile(
    r"(?:SLSH|\$)?\s*([\d,]+(?:\.\d+)?)\s+ayaad u dirtay\s+([^\(]+?)(?:\s*\(([^)]+)\))?(?:,Date|,|\.|$)",
    re.IGNORECASE
)

# Generic Sent: $10 u dirtay 0615551234 or Sent $10 to MOHAMED
GENERIC_SENT = re.compile(
    r"(?:SLSH|\$)?\s*([\d,]+(?:\.\d+)?)\s*(?:u dirtay|sent to|paid to|bixisay)\s+([^\(]+?)(?:\s*\(([^)]+)\))?(?:,Date|,|\.|$)",
    re.IGNORECASE
)


def _parse_amount(raw: str) -> float:
    """Parse '2,000.50' or '2000' into float."""
    try:
        return float(str(raw).replace(",", ""))
    except (ValueError, AttributeError):
        return 0.0


def _parse_timestamp(raw: str) -> str:
    """Convert 'DD/MM/YY HH:MM:SS' to ISO 8601 UTC string."""
    try:
        dt = datetime.strptime(raw.strip(), "%d/%m/%y %H:%M:%S")
        dt_utc = dt - timedelta(hours=3)
        return dt_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _clean_party(name_part: str, num_part: str = "", default_num: str = "") -> tuple[str, str | None]:
    """Clean name and number extracted from regex match."""
    name = (name_part or "").strip()
    num = (num_part or "").strip() or (default_num if default_num != "Forwarded SMS" else None)

    # If name is purely digits, treat it as number and name
    if name.isdigit() and not num:
        num = name

    if not name or name.isdigit():
        name = num or "Unknown"

    return name, num


def parse_sms(sms_body: str, sender_number: str = "") -> dict | None:
    """
    Parse an SMS body and return a structured transaction dict.
    Never extracts Reference numbers or TxIDs as transaction amounts!
    """
    if not sms_body or not sms_body.strip():
        return None

    body = re.sub(r"\s+", " ", sms_body).strip()
    combine = f"{body} {sender_number}".lower()

    # ── 1. Provider & Currency Detection ─────────────────────────────────────
    provider = "ZAAD"
    if "evc" in combine:
        provider = "EVC Plus"
    elif "edahab" in combine:
        provider = "eDahab"
    elif "sahal" in combine:
        provider = "Sahal"
    elif "mpesa" in combine or "m-pesa" in combine:
        provider = "M-Pesa"
    elif "zaad" in combine or "slsh" in combine or "ka heshay" in combine or "ayaad u dirtay" in combine or "hadhaagaaga" in combine:
        provider = "ZAAD"

    currency = "USD" if "$" in body and "slsh" not in combine else "SLSH"

    # ── 2. Transaction ID / Reference ─────────────────────────────────────────
    tix_match = REF_PATTERN.search(body)
    transaction_id = tix_match.group(1) if tix_match else None

    # ── 3. Timestamp ─────────────────────────────────────────────────────────
    tar_match = DATE_PATTERN.search(body)
    timestamp = _parse_timestamp(tar_match.group(1)) if tar_match else datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    # ── 4. Balance ───────────────────────────────────────────────────────────
    bal_match = BALANCE_PATTERN.search(body)
    balance = _parse_amount(bal_match.group(1)) if bal_match else None

    # ── 5. Match Structured Patterns (Received) ──────────────────────────────
    rcv_match = ENG_RECEIVED.search(body) or SOM_RECEIVED.search(body) or GENERIC_RECEIVED.search(body)
    if rcv_match:
        amount = _parse_amount(rcv_match.group(1))
        name, num = _clean_party(rcv_match.group(2), rcv_match.group(3), sender_number)
        return {
            "amount": amount,
            "currency": currency,
            "sender": name,
            "sender_number": num,
            "receiver": "You",
            "receiver_number": None,
            "provider": provider,
            "transaction_id": transaction_id,
            "timestamp": timestamp,
            "balance": balance,
            "type": "Received",
            "raw_sms": sms_body,
        }

    # ── 6. Match Structured Patterns (Sent) ──────────────────────────────────
    sent_match = ENG_SENT.search(body) or SOM_SENT.search(body) or GENERIC_SENT.search(body)
    if sent_match:
        amount = _parse_amount(sent_match.group(1))
        name, num = _clean_party(sent_match.group(2), sent_match.group(3))
        return {
            "amount": amount,
            "currency": currency,
            "sender": "You",
            "sender_number": None,
            "receiver": name,
            "receiver_number": num,
            "provider": provider,
            "transaction_id": transaction_id,
            "timestamp": timestamp,
            "balance": balance,
            "type": "Sent",
            "raw_sms": sms_body,
        }

    # ── 7. Safe Fallback (Avoid matching Ref/TxID as amount!) ────────────────
    clean_body_for_amount = REF_PATTERN.sub("", body)
    clean_body_for_amount = DATE_PATTERN.sub("", clean_body_for_amount)

    amount_match = re.search(r"(?:SLSH|\$)\s*([\d,]+(?:\.\d+)?)", clean_body_for_amount)
    if amount_match:
        amt_val = _parse_amount(amount_match.group(1))
        if amt_val > 0:
            is_sent = any(w in combine for w in ["sent", "dirtay", "bixisay", "paid", "debited", "to"])
            txn_type = "Sent" if is_sent else "Received"
            return {
                "amount": amt_val,
                "currency": currency,
                "sender": ("Sender" if sender_number == "Forwarded SMS" else sender_number) if not is_sent else "You",
                "sender_number": (sender_number if sender_number != "Forwarded SMS" else None) if not is_sent else None,
                "receiver": "You" if not is_sent else ("Recipient" if sender_number == "Forwarded SMS" else sender_number),
                "receiver_number": (sender_number if sender_number != "Forwarded SMS" else None) if is_sent else None,
                "provider": provider,
                "transaction_id": transaction_id,
                "timestamp": timestamp,
                "balance": balance,
                "type": txn_type,
                "raw_sms": sms_body,
            }

    print(f"[Parser] Could not extract transaction from SMS: {body[:120]}")
    return None
