package com.test.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.test.testutils.TestHelper;

class AppTest {

    @Test
    void testCompute() {
        assertEquals(4, App.compute(1, 2));
    }

    @Test
    void testHelper() {
        assertEquals(6, TestHelper.addAndDouble(1, 2));
    }
}
