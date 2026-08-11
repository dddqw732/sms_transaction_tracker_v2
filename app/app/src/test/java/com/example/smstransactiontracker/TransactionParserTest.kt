package com.example.smstransactiontracker

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import org.junit.Test

class TransactionParserTest {

  @Test
  fun parse_receivedPayPal() {
    val txn = TransactionParser.parse(
      "Received USD 120.50 from Jane Doe. Ref: TXN987654321",
      "PayPal"
    )
    assertNotNull(txn)
    assertEquals("Received", txn!!.type)
    assertEquals(120.50, txn.amount, 0.01)
    assertEquals("USD", txn.currency)
    assertEquals("Jane Doe", txn.sender)
    assertEquals("TXN987654321", txn.transactionId)
  }

  @Test
  fun parse_sentVenmo() {
    val txn = TransactionParser.parse(
      "You sent \$45.00 to John Smith via Venmo. Ref: VENMO001",
      "Venmo"
    )
    assertNotNull(txn)
    assertEquals("Sent", txn!!.type)
    assertEquals(45.0, txn.amount, 0.01)
    assertEquals("John Smith", txn.receiver)
  }

  @Test
  fun parse_mpesaSent() {
    val txn = TransactionParser.parse(
      "MPESA: KES 1,000 sent to +254712345678. Ref MPESAABC123",
      "MPESA"
    )
    assertNotNull(txn)
    assertEquals("Sent", txn!!.type)
    assertEquals(1000.0, txn.amount, 0.01)
    assertEquals("KES", txn.currency)
  }

  @Test
  fun parse_nonTransaction_returnsNull() {
    val txn = TransactionParser.parse("Hello, your verification code is 123456", "+15551234567")
    assertNull(txn)
  }
}
