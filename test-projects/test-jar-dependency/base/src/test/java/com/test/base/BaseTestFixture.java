package com.test.base;

public class BaseTestFixture {

    public static String fixtureValue() {
        return "fixture:" + Base.value();
    }
}
