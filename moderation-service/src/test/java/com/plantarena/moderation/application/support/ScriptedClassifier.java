package com.plantarena.moderation.application.support;

import com.plantarena.moderation.application.port.out.PlantClassifier;

/** Сценарный классификатор: preset-результат или включаемый сбой. */
public class ScriptedClassifier implements PlantClassifier {

    public Classification result = new Classification(true, 0.9f, "scripted-v1");
    public RuntimeException failure;

    @Override
    public Classification classify(byte[] imageBytes) {
        if (failure != null) {
            throw failure;
        }
        return result;
    }
}
