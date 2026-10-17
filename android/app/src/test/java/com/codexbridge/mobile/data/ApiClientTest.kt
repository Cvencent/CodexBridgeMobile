package com.codexbridge.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiClientTest {
    @Test
    fun commandResultPreservesPayloadAndSuccess() {
        assertEquals("{\"thread_id\":\"t1\",\"ok\":true}",
            ApiClient.commandPayload("""{"ok":true,"result":{"thread_id":"t1"}}"""))
    }

    @Test
    fun commandFailureDoesNotBecomeSuccess() {
        assertThrows(ApiException::class.java) {
            ApiClient.commandPayload("""{"ok":false,"error":"outcome_unknown"}""")
        }
    }

    @Test
    fun addsSchemeAndDefaultPortForBareHost() {
        assertEquals("http://192.168.1.5:8568", ApiClient.normalizeBase("192.168.1.5"))
    }

    @Test
    fun keepsExplicitPort() {
        assertEquals("http://192.168.1.5:9000", ApiClient.normalizeBase("http://192.168.1.5:9000"))
    }

    @Test
    fun trimsTrailingSlash() {
        assertEquals("http://192.168.1.5:8568", ApiClient.normalizeBase("http://192.168.1.5:8568/"))
    }

    @Test
    fun keepsHttpsAndHostname() {
        assertEquals("https://bridge.local:8568", ApiClient.normalizeBase("https://bridge.local"))
        assertEquals("http://bridge.local:8568", ApiClient.normalizeBase("bridge.local"))
    }

    @Test
    fun migratesTheOldDefaultPort() {
        assertEquals("http://192.168.1.5:8568", ApiClient.normalizeBase("http://192.168.1.5:8790/"))
        assertEquals("http://192.168.1.5:8568", ApiClient.normalizeBase("http://192.168.1.5:8791/"))
    }

    @Test
    fun storedAddressMigrationKeepsBlankValueUnpaired() {
        assertEquals("", ApiClient.migrateStoredBase(""))
        assertEquals("http://192.168.1.5:8568", ApiClient.migrateStoredBase("http://192.168.1.5:8790/"))
    }

    @Test
    fun explicitPortCanBeReplacedFromDiscovery() {
        assertEquals("http://192.168.1.5:8568", ApiClient.withPort("http://192.168.1.5:18791", 8568))
        assertEquals("http://192.168.1.5:9000/base", ApiClient.withPort("http://192.168.1.5:8568/base", 9000))
    }

    @Test
    fun rejectsBlankAddress() {
        assertThrows(ApiException::class.java) { ApiClient.normalizeBase("   ") }
    }
}
