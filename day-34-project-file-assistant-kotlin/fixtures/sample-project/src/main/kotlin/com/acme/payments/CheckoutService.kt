package com.acme.payments

class CheckoutService(
    private val legacyPaymentsApi: LegacyPaymentsApi,
    private val paymentClient: PaymentClient,
) {
    fun checkout(request: PaymentRequest): PaymentReceipt {
        legacyPaymentsApi.authorize(request.orderId, request.amountMinor)
        return paymentClient.charge(request)
    }

    fun refund(paymentId: String, reason: String): RefundReceipt =
        paymentClient.refund(paymentId, reason)
}
