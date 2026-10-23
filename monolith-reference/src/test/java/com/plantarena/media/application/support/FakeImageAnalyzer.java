package com.plantarena.media.application.support;

import com.plantarena.media.domain.AnalyzedImage;
import com.plantarena.media.domain.ImageAnalyzer;

/** Детерминированный анализатор для application-тестов: заданный результат. */
public class FakeImageAnalyzer implements ImageAnalyzer {

    public AnalyzedImage result;
    public RuntimeException failure;

    public FakeImageAnalyzer(AnalyzedImage result) {
        this.result = result;
    }

    @Override
    public AnalyzedImage analyze(byte[] content) {
        if (failure != null) {
            throw failure;
        }
        return result;
    }
}
