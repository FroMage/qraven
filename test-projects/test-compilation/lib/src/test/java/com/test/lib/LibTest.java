package com.test.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LibTest {

    @Test
    void testAdd() {
        assertEquals(3, Lib.add(1, 2));
    }
}
