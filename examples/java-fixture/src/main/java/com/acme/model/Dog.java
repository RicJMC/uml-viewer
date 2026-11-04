package com.acme.model;

import com.acme.api.Named;

public class Dog extends Animal implements Named {
    private final String name;

    public Dog(String name) {
        this.name = name;
    }

    @Override
    public String speak() {
        return "woof";
    }

    @Override
    public String name() {
        return name;
    }
}
