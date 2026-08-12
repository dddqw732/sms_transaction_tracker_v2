import sys
from pathlib import Path
sys.path.append(str(Path(__file__).parent))

from test_parser import parse_sms

samples = [
    {
        "name": "User Example 1 (English ZAAD Sent - no confirmed, no space in parens)",
        "sms": "[-[--ZAAD SHILLING--]-] Ref:15501073192 SLSH1,000 sent to CABDIMUXSIN CUMAR AXMED(7803712) at 12/08/26 10:50:51, Your Balance is SLSH1,000.3.",
        "amount": 1000.0,
        "currency": "SLSH",
        "type": "Sent",
        "party": "CABDIMUXSIN CUMAR AXMED",
        "party_num": "7803712",
        "txn_id": "15501073192",
        "balance": 1000.3
    },
    {
        "name": "User Example 2 (English ZAAD Received - confirmed, space in parens)",
        "sms": "[-[--ZAAD SHILLING--]-] Ref:15501086029 confirmed. SLSH1,000 Received from CABDIMUXSIN CUMAR AXMED (637803712),Date: 12/08/26 10:53:29, Your New A/C Balance is SLSH2,000.3",
        "amount": 1000.0,
        "currency": "SLSH",
        "type": "Received",
        "party": "CABDIMUXSIN CUMAR AXMED",
        "party_num": "637803712",
        "txn_id": "15501086029",
        "balance": 2000.3
    },
    {
        "name": "Somali ZAAD Received",
        "sms": "Waxaad SLSH1,000 ka heshay CABDIMUXSIN CUMAR AXMED (637803712). Hadhaagaaga:SLSH2,000.3. Tix:15501086029. Tar:12/08/26 10:53:29",
        "amount": 1000.0,
        "currency": "SLSH",
        "type": "Received",
        "party": "CABDIMUXSIN CUMAR AXMED",
        "party_num": "637803712",
        "txn_id": "15501086029",
        "balance": 2000.3
    },
    {
        "name": "Somali ZAAD Sent",
        "sms": "SLSH1,000 ayaad u dirtay CABDIMUXSIN CUMAR AXMED(7803712) Tar:12/08/26 10:50:51. Hadhaagaaga:SLSH1,000.3. Tix:15501073192",
        "amount": 1000.0,
        "currency": "SLSH",
        "type": "Sent",
        "party": "CABDIMUXSIN CUMAR AXMED",
        "party_num": "7803712",
        "txn_id": "15501073192",
        "balance": 1000.3
    }
]

failed = False
for s in samples:
    print(f"Testing: {s['name']}")
    res = parse_sms(s["sms"], "222")
    if not res:
        print(f"  FAIL: Could not parse!")
        failed = True
        continue
    
    if res["amount"] != s["amount"]:
        print(f"  FAIL Amount: {res['amount']} != {s['amount']}")
        failed = True
    if res["type"] != s["type"]:
        print(f"  FAIL Type: {res['type']} != {s['type']}")
        failed = True
    if res["transaction_id"] != s["txn_id"]:
        print(f"  FAIL TxID: {res['transaction_id']} != {s['txn_id']}")
        failed = True
    if res["balance"] != s["balance"]:
        print(f"  FAIL Balance: {res['balance']} != {s['balance']}")
        failed = True
        
    party_val = res["sender"] if res["type"] == "Received" else res["receiver"]
    party_num = res["sender_number"] if res["type"] == "Received" else res["receiver_number"]
    
    if party_val != s["party"]:
        print(f"  FAIL Party: {party_val} != {s['party']}")
        failed = True
    if party_num != s["party_num"]:
        print(f"  FAIL Party Num: {party_num} != {s['party_num']}")
        failed = True

    if not failed:
        print(f"  OK! Amount={res['amount']} {res['currency']} | Party={party_val} ({party_num}) | TxID={res['transaction_id']} | Bal={res['balance']}")

if failed:
    print("\nSOME TESTS FAILED!")
    sys.exit(1)
else:
    print("\nALL TEST CASES PASSED PERFECTLY!")
