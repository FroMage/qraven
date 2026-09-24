package com.test.lib

class KotlinServiceTest {
    fun testGreet() {
        val svc = KotlinService()
        assert(svc.greet("World") == "Hello, World!")
    }
}
