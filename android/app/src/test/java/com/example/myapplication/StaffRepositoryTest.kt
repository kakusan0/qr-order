package com.example.myapplication

import com.example.myapplication.data.model.OrderStatus
import com.example.myapplication.data.model.ReturnStatus
import com.example.myapplication.data.model.ReturnWanted
import com.example.myapplication.data.model.StaffRole
import com.example.myapplication.data.remote.ApiConfigStore
import com.example.myapplication.data.repository.GeoLocationParser
import com.example.myapplication.data.repository.MockStaffRepository
import com.example.myapplication.data.repository.QrOrderApiEndpoints
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffRepositoryTest {

    @Test
    fun geoLocationParserParsesDirectCoordinatesAndMapsUrls() {
        val direct = GeoLocationParser.parse("35.7100, 139.8107")
        assertNotNull(direct)
        assertEquals(35.7100, direct!!.first, 0.0001)
        assertEquals(139.8107, direct.second, 0.0001)

        val gmaps = GeoLocationParser.parse("https://www.google.com/maps/@35.659482,139.700559,17z")
        assertNotNull(gmaps)
        assertEquals(35.659482, gmaps!!.first, 0.0001)
        assertEquals(139.700559, gmaps.second, 0.0001)

        val appleMaps = GeoLocationParser.parse("https://maps.apple.com/?ll=35.648094,139.703198&q=Cafe")
        assertNotNull(appleMaps)
        assertEquals(35.648094, appleMaps!!.first, 0.0001)
        assertEquals(139.703198, appleMaps.second, 0.0001)

        assertNull(GeoLocationParser.parse("not a coordinate"))
    }

    @Test
    fun orderAgeCheckAndPartialFullRefundFlow() = runBlocking {
        val repo = MockStaffRepository()
        val initialOrders = repo.ordersFlow("shop-001").first()
        val target = initialOrders.first { it.id == "ord-004" }
        assertTrue(target.needsAgeCheck)

        assertTrue(repo.confirmAgeCheck("ord-004").isSuccess)
        val afterAge = repo.ordersFlow("shop-001").first().first { it.id == "ord-004" }
        assertFalse(afterAge.needsAgeCheck)

        assertTrue(repo.refundOrder("ord-004", 300).isSuccess)
        val partial = repo.ordersFlow("shop-001").first().first { it.id == "ord-004" }
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, partial.status)
        assertEquals(700, partial.refundableAmount)

        assertTrue(repo.refundOrder("ord-004", 700).isSuccess)
        val full = repo.ordersFlow("shop-001").first().first { it.id == "ord-004" }
        assertEquals(OrderStatus.REFUNDED, full.status)
        assertEquals(0, full.refundableAmount)
    }

    @Test
    fun productValidationAndDuplicateBarcodeCheck() = runBlocking {
        val repo = MockStaffRepository()
        // Price below 50 yen must fail
        assertTrue(repo.createProduct("shop-001", "テスト商品", 40, 8, null, false).isFailure)
        // Duplicate barcode must fail
        assertTrue(repo.createProduct("shop-001", "重複商品", 200, 8, "4901777018686", false).isFailure)
        // Valid product succeeds
        val created = repo.createProduct("shop-001", "抹茶ラテ", 580, 8, "4901085618448", false)
        assertTrue(created.isSuccess)
    }

    @Test
    fun returnDecisionAndLastAdminProtection() = runBlocking {
        val repo = MockStaffRepository()
        // Reject without note fails
        assertTrue(repo.decideReturn("ret-001", approve = false, resolution = null, staffNote = "").isFailure)
        // Approve refund and complete
        assertTrue(repo.decideReturn("ret-001", approve = true, resolution = ReturnWanted.REFUND, staffNote = "持参お願いします").isSuccess)
        assertTrue(repo.completeReturn("ret-001", refundAmount = 450).isSuccess)
        val completed = repo.returnsFlow("shop-001").first().first { it.id == "ret-001" }
        assertEquals(ReturnStatus.COMPLETED, completed.status)
        assertEquals(450, completed.refundAmount)

        // Cannot deactivate or delete the last admin
        assertTrue(repo.setStaffAccountActive("acc-001", false).isFailure)
        assertTrue(repo.deleteStaffAccount("acc-001").isFailure)

        // Staff role cannot refund
        repo.switchDemoRole(StaffRole.STAFF)
        assertTrue(repo.refundOrder("ord-003", 100).isFailure)
    }

    @Test
    fun apiConfigNormalizeBaseUrlHandlesVariousInputs() {
        assertEquals(
            QrOrderApiEndpoints.DEFAULT_BASE_URL,
            ApiConfigStore.normalizeBaseUrl("")
        )
        assertEquals(
            "https://qr-order.example.workers.dev",
            ApiConfigStore.normalizeBaseUrl("qr-order.example.workers.dev/")
        )
        assertEquals(
            "https://qr-order.example.workers.dev",
            ApiConfigStore.normalizeBaseUrl("https://qr-order.example.workers.dev/staff/login")
        )
        assertEquals(
            "http://10.0.2.2:8787",
            ApiConfigStore.normalizeBaseUrl("http://10.0.2.2:8787/")
        )
    }
}
