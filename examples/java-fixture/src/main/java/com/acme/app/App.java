package com.acme.app;

import com.acme.api.Named;
import com.acme.model.Dog;
import com.acme.store.Store;
import java.util.List;

public class App {
    private final Store store;

    public App(Store store) {
        this.store = store;
    }

    public String describe(List<Dog> dogs) {
        return store.count() + " animals: " + dogs.stream().map(Named::name).toList();
    }
}
