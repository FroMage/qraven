package com.test.app;

import org.apache.commons.lang3.StringUtils;

import com.test.util.StringHelper;

public class App {

    public static String process(String input) {
        String capitalized = StringHelper.capitalizeFirst(input);
        return StringUtils.abbreviate(capitalized, 20);
    }
}
