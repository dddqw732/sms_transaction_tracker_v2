"""
Backend API test script - verifies transaction POST and GET endpoints.
Run with: uv run --with requests python test_api.py
"""
import json
import sys
import time

try:
    import requests
except ImportError:
    print("requests not available, trying urllib")
    requests = None

BASE_URL = "http://localhost:8000"

SAMPLE_TRANSACTIONS = [
    {
        "amount": 120.50,
        "currency": "USD",
        "sender": "Jane Doe",
        "receiver": "You",
        "provider": "PayPal",
        "transaction_id": "TXN987654321",
        "timestamp": "2026-06-30T04:00:00Z",
        "type": "Received",
        "raw_sms": "Received USD 120.50 from Jane Doe. Ref: TXN987654321"
    },
    {
        "amount": 45.00,
        "currency": "USD",
        "sender": "You",
        "receiver": "John Smith",
        "provider": "Venmo",
        "transaction_id": "VENMO001",
        "timestamp": "2026-06-30T03:30:00Z",
        "type": "Sent",
        "raw_sms": "You sent $45.00 to John Smith via Venmo. Ref: VENMO001"
    },
    {
        "amount": 1000.00,
        "currency": "KES",
        "sender": "You",
        "receiver": "+254712345678",
        "provider": "M-Pesa",
        "transaction_id": "MPESAABC123",
        "timestamp": "2026-06-30T02:15:00Z",
        "type": "Sent",
        "raw_sms": "MPESA: KES 1,000 sent to +254712345678. Ref MPESAABC123"
    },
    {
        "amount": 25.00,
        "currency": "USD",
        "sender": "@john_cashapp",
        "receiver": "You",
        "provider": "CashApp",
        "transaction_id": None,
        "timestamp": "2026-06-30T01:45:00Z",
        "type": "Received",
        "raw_sms": "CashApp: You received $25 from @john_cashapp"
    },
]

def test_health():
    print("1. Testing server health (GET /)...")
    try:
        r = requests.get(BASE_URL, timeout=3)
        # 200 = served dashboard HTML, not JSON
        print(f"   OK Server responded with HTTP {r.status_code}")
        return True
    except Exception as e:
        print(f"   FAIL Server not reachable: {e}")
        return False

def test_auth():
    print("\n1b. Testing authentication (Signup/Login)...")
    company_payload = {
        "company_name": "Test Company API",
        "city": "Hargeisa",
        "initial_subscription_plan": "Enterprise",
        "password": "testpassword123",
        "status": "Active"
    }
    r = requests.post(f"{BASE_URL}/api/auth/signup", json=company_payload, timeout=5)
    if r.status_code == 200:
        data = r.json()
        token = data.get("access_token")
        print(f"   OK Registered company '{data['company']['company_code']}'")
        return token
    
    # If already created, login
    login_payload = {
        "company_code": "TES001",
        "password": "testpassword123"
    }
    r = requests.post(f"{BASE_URL}/api/auth/login", json=login_payload, timeout=5)
    if r.status_code == 200:
        data = r.json()
        token = data.get("access_token")
        print(f"   OK Logged in company TES001")
        return token
        
    print(f"   FAIL Auth failed: {r.status_code} {r.text}")
    return None

def test_post_transactions(token):
    print("\n2. Posting test transactions...")
    headers = {"Authorization": f"Bearer {token}"}
    ids = []
    for txn in SAMPLE_TRANSACTIONS:
        r = requests.post(f"{BASE_URL}/api/transactions", json=txn, headers=headers, timeout=5)
        if r.status_code in (200, 201):
            data = r.json()
            print(f"   OK Posted '{txn['type']} {txn['currency']} {txn['amount']}' -> id={data.get('id')}")
            ids.append(data.get('id'))
        else:
            print(f"   FAIL Failed: HTTP {r.status_code} — {r.text[:200]}")
    return ids

def test_duplicate(token):
    print("\n3. Testing duplicate rejection...")
    headers = {"Authorization": f"Bearer {token}"}
    txn = SAMPLE_TRANSACTIONS[0]  # already posted above
    r = requests.post(f"{BASE_URL}/api/transactions", json=txn, headers=headers, timeout=5)
    if r.status_code == 400:
        print("   OK Duplicate correctly rejected (HTTP 400)")
    else:
        print(f"   FAIL Expected HTTP 400, got {r.status_code}")

def test_get_all(token):
    print("\n4. Testing GET /api/transactions...")
    headers = {"Authorization": f"Bearer {token}"}
    r = requests.get(f"{BASE_URL}/api/transactions", headers=headers, timeout=5)
    if r.status_code == 200:
        data = r.json()
        print(f"   OK Retrieved {len(data)} transactions")
    else:
        print(f"   FAIL Failed: HTTP {r.status_code}")

def test_filter_type(token):
    print("\n5. Testing filter by type=Received...")
    headers = {"Authorization": f"Bearer {token}"}
    r = requests.get(f"{BASE_URL}/api/transactions?type=Received", headers=headers, timeout=5)
    if r.status_code == 200:
        data = r.json()
        all_received = all(t['type'] == 'Received' for t in data)
        print(f"   OK Got {len(data)} 'Received' transactions — all correct: {all_received}")
    else:
        print(f"   FAIL Failed: HTTP {r.status_code}")

def test_search(token):
    print("\n6. Testing search for 'Jane'...")
    headers = {"Authorization": f"Bearer {token}"}
    r = requests.get(f"{BASE_URL}/api/transactions?search=Jane", headers=headers, timeout=5)
    if r.status_code == 200:
        data = r.json()
        print(f"   OK Search returned {len(data)} result(s): {[t['sender'] for t in data]}")
    else:
        print(f"   FAIL Failed: HTTP {r.status_code}")

def test_sort_amount(token):
    print("\n7. Testing sort by amount ascending...")
    headers = {"Authorization": f"Bearer {token}"}
    r = requests.get(f"{BASE_URL}/api/transactions?sort_by=amount&sort_order=asc", headers=headers, timeout=5)
    if r.status_code == 200:
        data = r.json()
        amounts = [t['amount'] for t in data]
        is_sorted = amounts == sorted(amounts)
        print(f"   OK Amounts in order: {amounts} — sorted: {is_sorted}")
    else:
        print(f"   FAIL Failed: HTTP {r.status_code}")

if __name__ == "__main__":
    if not requests:
        print("Install requests first: pip install requests")
        sys.exit(1)
        
    print("=" * 55)
    print("  SMS Transaction Tracker — Backend API Test Suite")
    print("=" * 55)
    
    if not test_health():
        print("\n  FAIL Server is not running. Start it first and retry.")
        sys.exit(1)
        
    token = test_auth()
    if not token:
        print("\n  FAIL Could not authenticate.")
        sys.exit(1)
    
    test_post_transactions(token)
    test_duplicate(token)
    test_get_all(token)
    test_filter_type(token)
    test_search(token)
    test_sort_amount(token)
    
    print("\n" + "=" * 55)
    print("  All tests complete!")
    print("=" * 55)
