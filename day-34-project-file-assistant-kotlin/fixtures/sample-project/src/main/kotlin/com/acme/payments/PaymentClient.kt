package com.acme.payments

data class PaymentRequest(val orderId: String, val amountMinor: Long)
data class PaymentReceipt(val paymentId: String)
data class RefundReceipt(val refundId: String)

interface PaymentClient {
    fun charge(request: PaymentRequest): PaymentReceipt
    fun refund(paymentId: String, reason: String): RefundReceipt
}
