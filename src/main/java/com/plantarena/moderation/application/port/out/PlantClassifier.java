package com.plantarena.moderation.application.port.out;


public interface PlantClassifier {

    Classification classify(byte[] imageBytes);

    
    record Classification(boolean plant, float confidence, String modelVersion) {
    }
}
