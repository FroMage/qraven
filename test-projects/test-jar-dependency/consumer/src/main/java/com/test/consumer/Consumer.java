package com.test.consumer;

import com.test.base.Base;

public class Consumer {

    public static String consume() {
        return "consumed:" + Base.value();
    }
}
