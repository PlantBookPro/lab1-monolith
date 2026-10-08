package com.plantarena.moderation.adapter.out.classifier;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.plantarena.moderation.application.port.out.ClassifierUnavailableException;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;


public class OnnxPlantClassifier implements PlantClassifier {

    public static final String MODEL_VERSION = "mobilenetv2-1.0-onnx-imagenet/plant-classes-v1";
    static final float CONFIDENCE_THRESHOLD = 0.35f;
    private static final int INPUT_SIZE = 224;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    
    static final Set<Integer> PLANT_CLASS_INDICES = Set.of(
        936, 937, 938, 941, 944,       
        950, 951, 952, 953, 954, 956,  
        958,                           
        984, 985, 986, 987,            
        988, 989, 990, 998);           

    private final Path modelPath;

    public OnnxPlantClassifier(Path modelPath) {
        this.modelPath = modelPath;
    }

    @Override
    public Classification classify(byte[] imageBytes) {
        if (!Files.isReadable(modelPath)) {
            throw new ClassifierUnavailableException(
                "Модель классификатора не найдена: " + modelPath.toAbsolutePath()
                    + " — см. README «Модерация» (mvnw сам скачивает модель при сборке; "
                    + "offline-сборка оставляет задания в RETRY)");
        }
        float[] probabilities = runInference(preprocess(imageBytes));
        int top = argmax(probabilities);
        float confidence = probabilities[top];
        boolean plant = PLANT_CLASS_INDICES.contains(top) && confidence >= CONFIDENCE_THRESHOLD;
        return new Classification(plant, confidence, MODEL_VERSION);
    }

    
    private float[] preprocess(byte[] imageBytes) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            throw new ClassifierUnavailableException("Не удалось декодировать изображение", e);
        }
        if (source == null) {
            throw new ClassifierUnavailableException("Не удалось декодировать изображение");
        }
        BufferedImage resized = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, INPUT_SIZE, INPUT_SIZE, null);
        } finally {
            graphics.dispose();
        }
        float[] tensor = new float[3 * INPUT_SIZE * INPUT_SIZE];
        int plane = INPUT_SIZE * INPUT_SIZE;
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                int pixel = y * INPUT_SIZE + x;
                tensor[pixel] = (((rgb >> 16) & 0xFF) / 255f - MEAN[0]) / STD[0];
                tensor[plane + pixel] = (((rgb >> 8) & 0xFF) / 255f - MEAN[1]) / STD[1];
                tensor[2 * plane + pixel] = ((rgb & 0xFF) / 255f - MEAN[2]) / STD[2];
            }
        }
        return tensor;
    }

    private float[] runInference(float[] input) {
        try (OrtEnvironment environment = OrtEnvironment.getEnvironment();
             OrtSession.SessionOptions options = new OrtSession.SessionOptions();
             OrtSession session = environment.createSession(modelPath.toString(), options);
             OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input),
                 new long[] {1, 3, INPUT_SIZE, INPUT_SIZE})) {
            String inputName = session.getInputNames().iterator().next();
            try (OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
                float[][] logits = (float[][]) result.get(0).getValue();
                return softmax(logits[0]);
            }
        } catch (Exception e) {
            throw new ClassifierUnavailableException("Инференс ONNX не удался", e);
        }
    }

    private static int argmax(float[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    private static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float value : logits) {
            max = Math.max(max, value);
        }
        float[] probabilities = new float[logits.length];
        double sum = 0;
        for (int i = 0; i < logits.length; i++) {
            probabilities[i] = (float) Math.exp(logits[i] - max);
            sum += probabilities[i];
        }
        for (int i = 0; i < probabilities.length; i++) {
            probabilities[i] /= (float) sum;
        }
        return probabilities;
    }
}
