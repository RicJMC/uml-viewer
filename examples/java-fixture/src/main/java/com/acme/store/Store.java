package com.acme.store;

import com.acme.model.Animal;
import java.util.List;

public class Store {
    private final List<Animal> animals;

    public Store(List<Animal> animals) {
        this.animals = animals;
    }

    public int count() {
        return animals.size();
    }
}
