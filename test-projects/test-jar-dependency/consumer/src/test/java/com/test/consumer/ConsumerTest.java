package com.test.consumer;

import com.test.base.BaseTestFixture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsumerTest {

    @Test
    void testConsume() {
        assertEquals("consumed:base", Consumer.consume());
    }

    @Test
    void testFixture() {
        assertEquals("fixture:base", BaseTestFixture.fixtureValue());
    }
}
