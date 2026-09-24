package com.test.testutils;

import com.test.lib.Lib;

public class TestHelper {

    public static int addAndDouble(int a, int b) {
        return Lib.add(a, b) * 2;
    }
}
