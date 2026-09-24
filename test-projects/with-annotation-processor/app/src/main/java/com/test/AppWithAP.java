package com.test;

import com.test.processor.Generate;

@Generate
public class AppWithAP {

    public String value() {
        return "processed";
    }
}
