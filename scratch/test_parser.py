import re
from datetime import datetime, timezone, timedelta

REF_PATTERN = re.compile(
    r"(?:Ref|Tix|TxID|TrxId|Reference|Id)\s*:\s*([A-Za-z0-9]+)",
    re.IGNORECASE
)

DATE_PATTERN = re.compile(
    r"(?:Date|Tar|at)\s*:?\s*(\d{2}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2})",
    re.IGNORECASE
)

BALANCE_PATTERN = re.compile(
    r"(?:Hadhaagaaga|Balance|A/C Balance|New Balance|New A/C Balance)\s*(?:is|:)?\s*(?:SLSH|\$|USD)?\s*([\d,]+(?:\.\d+)?)",
    re.IGNORECASE
)

RCV_PATTERN = re.compile(
    r"(?:Waxaad\s+)?(?:SLSH|\$|USD)?\s*([\d,]+(?:\.\d+)?)\s*(?:ka heshay|Received from|received from|ka socda)\s+([^\.,\n]+?)(?:,Date|,at|,Tar|,|\.|$)",
    re.IGNORECASE
)

SENT_PATTERN = re.compile(
    r"(?:SLSH|\$|USD)?\s*([\d,]+(?:\.\d+)?)\s*(?:sent to|Sent to|ayaad u dirtay|u dirtay|bixisay|paid to)\s+([^\.,\n]+?)(?:,Date|,at|,Tar|,|\.|$)",
    re.IGNORECASE
)


def _parse_amount(raw: str) -> float:
    try:
        return float(str(raw).replace(",", ""))
    except (ValueError, AttributeError):
        return 0.0


def _parse_timestamp(raw: str) -> str:
    try:
        dt = datetime.strptime(raw.strip(), "%d/%m/%y %H:%M:%S")
        dt_utc = dt - timedelta(hours=3)
        return dt_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _extract_party(party_str: str, default_num: str = "") -> tuple[str, str | None]:
    if not party_str:
        return "Unknown", (default_num if default_num != "Forwarded SMS" else None)

    party_str = party_str.strip()

    paren_match = re.search(r"^([^\(]+?)\s*\(([^)]+)\)", party_str)
    if paren_match:
        part1 = paren_match.group(1).strip()
        part2 = paren_match.group(2).strip()
        if part1.isdigit() and not part2.isdigit():
            return part2, part1
        elif not part1.isdigit() and part2.isdigit():
            return part1, part2
        else:
            return part1, part2

    if party_str.isdigit():
        return party_str, party_str
    else:
        num = default_num if (default_num and default_num != "Forwarded SMS") else None
        return party_str, num


def parse_sms(sms_body: str, sender_number: str = "") -> dict | None:
    if not sms_body or not sms_body.strip():
        return None

    body = re.sub(r"\s+", " ", sms_body).strip()
    combine = f"{body} {sender_number}".lower()

    # Provider & Currency
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

    # Transaction ID
    tix_match = REF_PATTERN.search(body)
    transaction_id = tix_match.group(1) if tix_match else None

    # Timestamp
    tar_match = DATE_PATTERN.search(body)
    timestamp = _parse_timestamp(tar_match.group(1)) if tar_match else datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    # Balance
    bal_match = BALANCE_PATTERN.search(body)
    balance = _parse_amount(bal_match.group(1)) if bal_match else None

    # Received Match
    rcv_match = RCV_PATTERN.search(body)
    if rcv_match:
        amount = _parse_amount(rcv_match.group(1))
        name, num = _extract_party(rcv_match.group(2), sender_number)
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

    # Sent Match
    sent_match = SENT_PATTERN.search(body)
    if sent_match:
        amount = _parse_amount(sent_match.group(1))
        name, num = _extract_party(sent_match.group(2))
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

    # Fallback (never use Ref ID as amount)
    clean_body = REF_PATTERN.sub("", body)
    clean_body = DATE_PATTERN.sub("", clean_body)

    amount_match = re.search(r"(?:SLSH|\$|USD)\s*([\d,]+(?:\.\d+)?)", clean_body)
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

    return None
