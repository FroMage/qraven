package com.test.core;

public class CoreUtil {

    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase();
    }
}
