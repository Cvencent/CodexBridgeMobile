package com.codexbridge.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRouteTest {
    @Test
    fun wifiPrefersLanThenFallsBackToWan() {
        val candidates = ConnectionRoute.candidates(
            lanUrl = "http://192.168.0.42:8568",
            wanUrl = "http://tokenplanx.vip:8568",
            onWifi = true,
        )

        assertEquals(
            listOf(
                RouteEndpoint("http://192.168.0.42:8568", ConnectionRole.Lan),
                RouteEndpoint("http://tokenplanx.vip:8568", ConnectionRole.Wan),
            ),
            candidates,
        )
    }

    @Test
    fun cellularPrefersWanThenFallsBackToLan() {
        val candidates = ConnectionRoute.candidates(
            lanUrl = "http://192.168.0.42:8568",
            wanUrl = "http://tokenplanx.vip:8568",
            onWifi = false,
        )

        assertEquals(
            listOf(
                RouteEndpoint("http://tokenplanx.vip:8568", ConnectionRole.Wan),
                RouteEndpoint("http://192.168.0.42:8568", ConnectionRole.Lan),
            ),
            candidates,
        )
    }

    @Test
    fun missingRouteIsSkippedWithoutDuplicatingTheOther() {
        val candidates = ConnectionRoute.candidates(
            lanUrl = "http://192.168.0.42:8568",
            wanUrl = "",
            onWifi = false,
        )

        assertEquals(
            listOf(RouteEndpoint("http://192.168.0.42:8568", ConnectionRole.Lan)),
            candidates,
        )
    }

    @Test
    fun identicalRoutesAreCollapsed() {
        val candidates = ConnectionRoute.candidates(
            lanUrl = "http://192.168.0.42:8568/",
            wanUrl = "http://192.168.0.42:8568",
            onWifi = true,
        )

        assertEquals(
            listOf(RouteEndpoint("http://192.168.0.42:8568", ConnectionRole.Lan)),
            candidates,
        )
    }

    @Test
    fun legacySingleAddressBecomesTheActiveRoute() {
        val routes = ConnectionRoute.fromStored(
            legacyUrl = "http://192.168.0.42:8568",
            lanUrl = "",
            wanUrl = "",
        )

        assertEquals("http://192.168.0.42:8568", routes.primary)
        assertTrue(routes.hasUsableRoute)
        assertFalse(routes.hasDualRoutes)
    }

    @Test

    fun dualAddressesAreStoredIndependently() {
        val routes = ConnectionRoute.fromStored(
            legacyUrl = "http://192.168.0.42:8568",
            lanUrl = "http://192.168.0.42:8568",
            wanUrl = "http://tokenplanx.vip:8568",
        )

        assertEquals("http://192.168.0.42:8568", routes.lanUrl)
        assertEquals("http://tokenplanx.vip:8568", routes.wanUrl)
        assertTrue(routes.hasDualRoutes)
    }
    @Test
    fun selectorFallsBackWhenPreferredRouteFails() = kotlinx.coroutines.runBlocking {
        val routes = ConnectionRoute.candidates(
            lanUrl = "http://192.168.0.42:8568",
            wanUrl = "http://tokenplanx.vip:8568",
            onWifi = true,
        )

        val selected = ConnectionSelector.select(routes) { endpoint ->
            if (endpoint.role == ConnectionRole.Lan) {
                error("lan unavailable")
            }
            PingResponse(ok = true, service = "codexbridge-companion", port = 8568)
        }

        assertEquals("http://tokenplanx.vip:8568", selected?.url)
        assertEquals(ConnectionRole.Wan, selected?.endpoint?.role)
    }

    @Test
    fun selectorReturnsNullWhenNoRouteAnswers() = kotlinx.coroutines.runBlocking {
        val routes = ConnectionRoute.candidates(
            lanUrl = "http://192.168.0.42:8568",
            wanUrl = "http://tokenplanx.vip:8568",
            onWifi = true,
        )

        val selected = ConnectionSelector.select(routes) { null }

        assertEquals(null, selected)
    }

    @Test
    fun selectorPreservesProxyPortFromTheChosenRoute() = kotlinx.coroutines.runBlocking {
        val routes = ConnectionRoute.candidates(
            lanUrl = "http://192.168.0.42:8568",
            wanUrl = "http://tokenplanx.vip:8080",
            onWifi = false,
        )

        val selected = ConnectionSelector.select(routes) {
            PingResponse(ok = true, service = "codexbridge-companion", port = 8568)
        }

        assertEquals("http://tokenplanx.vip:8080", selected?.url)
    }


    @Test
    fun onlyWanConfiguredDoesNotFallBackToLegacyLan() {
        val routes = ConnectionRoute.candidatesFor(
            legacyUrl = "http://192.168.0.42:8568",
            lanUrl = "",
            wanUrl = "http://tokenplanx.vip:8080",
            onWifi = true,
        )

        assertEquals(
            listOf(RouteEndpoint("http://tokenplanx.vip:8080", ConnectionRole.Wan)),
            routes,
        )
    }

    @Test
    fun configuredRouteKeepsReverseProxyPort() {
        assertEquals(
            "http://tokenplanx.vip:8080",
            ConnectionRoute.endpointForConfiguredRoute("tokenplanx.vip:8080"),
        )
    }
}