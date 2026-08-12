import re
from datetime import datetime, timezone, timedelta


# ─── Regex patterns ─────────────────────────────────────────────────────────
# ZAAD Sent:     SLSH 3,000 ayaad u dirtay NAME (NUMBER)
# ZAAD Received: Waxaad SLSH1,000 ka heshay NAME (NUMBER)
# Balance:       Hadhaagaaga:SLSH5,000
# Tix ID:        Tix:15189318791 or TxID: 12345
# Time:          Tar:02/07/26 22:43:20

ZAAD_SENT = re.compile(
    r"(?:SLSH|\$)?\s*([\d,]+(?:\.\d+)?)\s*ayaad u dirtay\s+([^\(\.]+?)(?:\s*\(([^)]+)\))?(?:\.|\s|$)",
    re.IGNORECASE
)
ZAAD_RECEIVED = re.compile(
    r"Waxaad\s+(?:SLSH|\$)?\s*([\d,]+(?:\.\d+)?)\s*ka heshay\s+([^\(\.]+?)(?:\s*\(([^)]+)\))?(?:\.|\s|$)",
    re.IGNORECASE
)
ZAAD_BALANCE = re.compile(r"Hadhaagaaga\s*:\s*(?:SLSH|\$)?\s*([\d,]+(?:\.\d+)?)", re.IGNORECASE)
ZAAD_TIX = re.compile(r"(?:Tix|TxID|Ref|Id)\s*:\s*([A-Za-z0-9]+)", re.IGNORECASE)
ZAAD_TAR = re.compile(r"(?:Tar|Date)\s*:\s*(\d{2}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2})", re.IGNORECASE)


def _parse_amount(raw: str) -> float:
    """Parse '3,000.50' or '1000' into float."""
    try:
        return float(str(raw).replace(",", ""))
    except (ValueError, AttributeError):
        return 0.0


def _parse_zaad_timestamp(raw: str) -> str:
    """Convert 'DD/MM/YY HH:MM:SS' to ISO 8601 UTC string."""
    try:
        dt = datetime.strptime(raw.strip(), "%d/%m/%y %H:%M:%S")
        dt_utc = dt - timedelta(hours=3)
        return dt_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_sms(sms_body: str, sender_number: str = "") -> dict | None:
    """
    Parse an SMS body and return a structured transaction dict.
    """
    if not sms_body or not sms_body.strip():
        return None

    body = re.sub(r"\s+", " ", sms_body).strip()
    combine = f"{body} {sender_number}".lower()

    # ── Detect provider ──────────────────────────────────────────────────────
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
        receiver_name = (sent_match.group(2) or "Recipient").strip()
        receiver_num = (sent_match.group(3) or "").strip() or None
        return {
            "amount": amount,
            "currency": currency,
            "sender": "You",
            "sender_number": None,
            "receiver": receiver_name,
            "receiver_number": receiver_num,
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
        sender_name = (rcv_match.group(2) or "Sender").strip()
        sender_num = (rcv_match.group(3) or "").strip() or (sender_number if sender_number != "Forwarded SMS" else None)
        return {
            "amount": amount,
            "currency": currency,
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

    # ── Fallback Parsing for generic incoming payment SMS ──────────────────────
    amount_match = re.search(r"(?:SLSH|\$)?\s*([\d,]+(?:\.\d+)?)", body)
    if amount_match:
        raw_amt = amount_match.group(1)
        amt_val = _parse_amount(raw_amt)
        if amt_val > 0:
            is_sent = any(w in combine for w in ["sent", "dirtay", "bixisay", "paid", "debited", "to"])
            txn_type = "Sent" if is_sent else "Received"
            return {
                "amount": amt_val,
                "currency": currency,
                "sender": (sender_number if sender_number != "Forwarded SMS" else "Sender") if not is_sent else "You",
                "sender_number": (sender_number if sender_number != "Forwarded SMS" else None) if not is_sent else None,
                "receiver": "You" if not is_sent else (sender_number if sender_number != "Forwarded SMS" else "Recipient"),
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
