package com.acme.payments

interface LegacyPaymentsApi {
    fun authorize(orderId: String, amountMinor: Long): String
}
