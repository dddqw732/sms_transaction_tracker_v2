import re
from datetime import datetime, timezone


# ─── ZAAD-specific regex patterns ─────────────────────────────────────────────
# Sent:     SLSH 3,000 ayaad u dirtay NAME(NUMBER)
# Received: Waxaad SLSH1,000 ka heshay NAME (NUMBER)
# Balance:  Hadhaagaaga:SLSH5,000
# Tix ID:   Tix:15189318791 or Tix: 15189351747
# Time:     Tar:02/07/26 22:43:20

ZAAD_SENT = re.compile(
    r"SLSH\s*([\d,]+)\s+ayaad u dirtay\s+(.+?)\s*\((\d+)\)",
    re.IGNORECASE
)
ZAAD_RECEIVED = re.compile(
    r"Waxaad\s+SLSH\s*([\d,]+)\s+ka heshay\s+(.+?)\s*\((\d+)\)",
    re.IGNORECASE
)
ZAAD_BALANCE = re.compile(r"Hadhaagaaga\s*:\s*SLSH\s*([\d,]+)", re.IGNORECASE)
ZAAD_TIX = re.compile(r"Tix\s*:\s*(\d+)", re.IGNORECASE)
ZAAD_TAR = re.compile(r"Tar\s*:\s*(\d{2}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2})", re.IGNORECASE)


def _parse_amount(raw: str) -> float:
    """Parse '3,000' or '1000' into float."""
    return float(raw.replace(",", ""))


def _parse_zaad_timestamp(raw: str) -> str:
    """Convert 'DD/MM/YY HH:MM:SS' to ISO 8601 UTC string."""
    try:
        dt = datetime.strptime(raw.strip(), "%d/%m/%y %H:%M:%S")
        # Assume EAT (UTC+3) and convert to UTC
        from datetime import timedelta
        dt_utc = dt - timedelta(hours=3)
        return dt_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_sms(sms_body: str, sender_number: str) -> dict | None:
    """
    Parse an SMS body and return a structured transaction dict.
    Never invents values — missing fields are None.
    """
    # Normalise: collapse all whitespace runs to single space
    body = re.sub(r"\s+", " ", sms_body).strip()

    # ── Detect provider ──────────────────────────────────────────────────────
    provider = None
    body_lower = body.lower()
    if "zaad" in body_lower:
        provider = "ZAAD"
    elif "evc plus" in body_lower or "evcplus" in body_lower:
        provider = "EVC Plus"
    elif "edahab" in body_lower:
        provider = "eDahab"
    elif "sahal" in body_lower:
        provider = "Sahal"
    elif "mpesa" in body_lower or "m-pesa" in body_lower:
        provider = "M-Pesa"

    if provider is None:
        # Cannot identify provider — reject
        print(f"[Parser] Could not identify provider in: {body[:80]}")
        return None

    # ── Transaction ID ───────────────────────────────────────────────────────
    tix_match = ZAAD_TIX.search(body)
    transaction_id = tix_match.group(1) if tix_match else None

    # ── Timestamp ────────────────────────────────────────────────────────────
    tar_match = ZAAD_TAR.search(body)
    timestamp = _parse_zaad_timestamp(tar_match.group(1)) if tar_match else datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    # ── Balance ──────────────────────────────────────────────────────────────
    bal_match = ZAAD_BALANCE.search(body)
    balance = _parse_amount(bal_match.group(1)) if bal_match else None

    # ── Try SENT pattern ─────────────────────────────────────────────────────
    sent_match = ZAAD_SENT.search(body)
    if sent_match:
        amount = _parse_amount(sent_match.group(1))
        receiver_name = sent_match.group(2).strip()
        receiver_number = sent_match.group(3).strip()
        return {
            "amount": amount,
            "currency": "SLSH",
            "sender": "You",
            "sender_number": None,
            "receiver": receiver_name,
            "receiver_number": receiver_number,
            "provider": provider,
            "transaction_id": transaction_id,
            "timestamp": timestamp,
            "balance": balance,
            "type": "Sent",
            "raw_sms": sms_body,
        }

    # ── Try RECEIVED pattern ─────────────────────────────────────────────────
    rcv_match = ZAAD_RECEIVED.search(body)
    if rcv_match:
        amount = _parse_amount(rcv_match.group(1))
        sender_name = rcv_match.group(2).strip()
        sender_num = rcv_match.group(3).strip()
        return {
            "amount": amount,
            "currency": "SLSH",
            "sender": sender_name,
            "sender_number": sender_num,
            "receiver": "You",
            "receiver_number": None,
            "provider": provider,
            "transaction_id": transaction_id,
            "timestamp": timestamp,
            "balance": balance,
            "type": "Received",
            "raw_sms": sms_body,
        }

    print(f"[Parser] No pattern matched for provider {provider}: {body[:120]}")
    return None
