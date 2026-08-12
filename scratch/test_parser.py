import sys
from pathlib import Path
sys.path.append(str(Path(__file__).parent.parent / "server"))

from sms_parser import parse_sms

samples = [
    {
        "name": "English ZAAD Shilling Received",
        "sms": "[-[--ZAAD SHILLING--]-] Ref:15501107102 confirmed. SLSH2,000 Received from CABDIMUXSIN CUMAR AXMED (637803712),Date: 12/08/26 10:54:59, Your New A/C Balance is SLSH4,000.3",
        "amount": 2000.0,
        "currency": "SLSH",
        "sender": "CABDIMUXSIN CUMAR AXMED",
        "sender_number": "637803712",
        "type": "Received",
        "txn_id": "15501107102",
        "balance": 4000.3,
    },
    {
        "name": "English ZAAD Shilling Sent",
        "sms": "[-[--ZAAD SHILLING--]-] Ref:9988776655 confirmed. SLSH5,500 Sent to AMINA HASSAN (634443322),Date: 12/08/26 11:00:00, Your New A/C Balance is SLSH1,200.0",
        "amount": 5500.0,
        "currency": "SLSH",
        "receiver": "AMINA HASSAN",
        "receiver_number": "634443322",
        "type": "Sent",
        "txn_id": "9988776655",
        "balance": 1200.0,
    },
    {
        "name": "Somali ZAAD Received",
        "sms": "Waxaad SLSH 1,000 ka heshay MOHAMED (0634123456). Hadhaagaaga:SLSH 5,000. Tix:15189318791. Tar:02/07/26 22:43:20",
        "amount": 1000.0,
        "currency": "SLSH",
        "sender": "MOHAMED",
        "sender_number": "0634123456",
        "type": "Received",
        "txn_id": "15189318791",
        "balance": 5000.0,
    },
]

for s in samples:
    print(f"Testing: {s['name']}")
    res = parse_sms(s["sms"], "222")
    assert res is not None, f"Failed parsing {s['name']}"
    assert res["amount"] == s["amount"], f"Amount fail: {res['amount']} != {s['amount']}"
    assert res["currency"] == s["currency"], f"Currency fail: {res['currency']} != {s['currency']}"
    assert res["type"] == s["type"], f"Type fail: {res['type']} != {s['type']}"
    assert res["transaction_id"] == s["txn_id"], f"TxID fail: {res['transaction_id']} != {s['txn_id']}"
    assert res["balance"] == s["balance"], f"Balance fail: {res['balance']} != {s['balance']}"
    if s["type"] == "Received":
        assert res["sender"] == s["sender"], f"Sender fail: {res['sender']} != {s['sender']}"
        assert res["sender_number"] == s["sender_number"], f"Sender num fail: {res['sender_number']} != {s['sender_number']}"
    else:
        assert res["receiver"] == s["receiver"], f"Receiver fail: {res['receiver']} != {s['receiver']}"
        assert res["receiver_number"] == s["receiver_number"], f"Receiver num fail: {res['receiver_number']} != {s['receiver_number']}"
    print(f"  OK: Amount={res['amount']} {res['currency']} | Party={res['sender'] if res['type']=='Received' else res['receiver']} | TxID={res['transaction_id']}")

print("\nALL SMS PARSER TESTS PASSED SUCCESSFULLY!")
