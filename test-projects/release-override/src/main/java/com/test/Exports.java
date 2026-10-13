package com.test;

import jdk.internal.module.Modules;

public class Exports {
    public static Class<?> modules() {
        return Modules.class;
    }
}
