package com.test.util;

import com.test.core.CoreUtil;

public class StringHelper {

    public static String capitalizeFirst(String input) {
        String normalized = CoreUtil.normalize(input);
        if (normalized.isEmpty()) {
            return normalized;
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
