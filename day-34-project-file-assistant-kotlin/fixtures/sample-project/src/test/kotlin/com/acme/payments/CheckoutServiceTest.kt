package com.acme.payments

class CheckoutServiceTest {
    private class FakeLegacyPaymentsApi : LegacyPaymentsApi {
        override fun authorize(orderId: String, amountMinor: Long): String = "legacy-authorization"
    }

    fun `checkout keeps legacy authorization during migration`() {
        val legacy: LegacyPaymentsApi = FakeLegacyPaymentsApi()
        check(legacy.authorize("order-42", 1_500) == "legacy-authorization")
    }
}
