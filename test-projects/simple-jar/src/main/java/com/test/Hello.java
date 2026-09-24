package com.test;

public class Hello {

    private final String name;

    public Hello(String name) {
        this.name = name;
    }

    public String greet() {
        return "Hello, " + name + "!";
    }
}
