package com.handdraw.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerOptions;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HandDrawAR";
    private static final int CAMERA_PERMISSION_CODE = 100;

    // UI элементы
    private PreviewView previewView;
    private DrawingView drawingView;
    private TextView statusText;
    private TextView fpsText;
    private FrameLayout loadingScreen;
    private View statusDot1;
    private View statusDot2;

    // MediaPipe
    private HandLandmarker handLandmarker;
    private ExecutorService cameraExecutor;

    // FPS счётчик
    private int frameCount = 0;
    private long lastFpsTime = System.currentTimeMillis();

    // Состояние жестов (рука 1)
    private String confirmedGesture1 = "none";
    private String pendingGesture1 = "none";
    private int gestureFrames1 = 0;
    private boolean isDrawing1 = false;
    private float smoothX1 = 0f;
    private float smoothY1 = 0f;

    // Состояние жестов (рука 2)
    private String confirmedGesture2 = "none";
    private String pendingGesture2 = "none";
    private int gestureFrames2 = 0;
    private boolean isDrawing2 = false;
    private float smoothX2 = 0f;
    private float smoothY2 = 0f;

    // Константы
    private static final float SMOOTH_FACTOR = 0.35f;
    private static final float PINCH_THRESHOLD = 0.07f;
    private static final int GESTURE_THRESHOLD = 2;

    // Цвета
    private final int[] colors = {
        Color.parseColor("#ff4757"),
        Color.parseColor("#2ed573"),
        Color.parseColor("#1e90ff"),
        Color.parseColor("#ffa502"),
        Color.parseColor("#ffffff"),
        Color.parseColor("#a55eea")
    };
    private View[] colorViews;
    private int selectedColorIndex = 0;

    // Timestamp для MediaPipe
    private long lastTimestamp = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Полноэкранный режим
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        setContentView(R.layout.activity_main);

        // Инициализация UI
        previewView = findViewById(R.id.previewView);
        drawingView = findViewById(R.id.drawingView);
        statusText = findViewById(R.id.statusText);
        fpsText = findViewById(R.id.fpsText);
        loadingScreen = findViewById(R.id.loadingScreen);
        statusDot1 = findViewById(R.id.statusDot1);
        statusDot2 = findViewById(R.id.statusDot2);

        Button btnClear = findViewById(R.id.btnClear);
        Button btnUndo = findViewById(R.id.btnUndo);
        Button btnThickness = findViewById(R.id.btnThickness);

        btnClear.setOnClickListener(v -> drawingView.clearAll());
        btnUndo.setOnClickListener(v -> drawingView.undo());
        btnThickness.setOnClickListener(v -> {
            drawingView.nextThickness();
        });

        // Цвета
        colorViews = new View[]{
            findViewById(R.id.colorRed),
            findViewById(R.id.colorGreen),
            findViewById(R.id.colorBlue),
            findViewById(R.id.colorOrange),
            findViewById(R.id.colorWhite),
            findViewById(R.id.colorPurple)
        };

        for (int i = 0; i < colorViews.length; i++) {
            final int idx = i;
            colorViews[i].setOnClickListener(v -> selectColor(idx));
        }
        updateColorSelection();

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Проверяем разрешение камеры
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            initializeApp();
        } else {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeApp();
        } else {
            statusText.setText("Нет доступа к камере!");
            loadingScreen.setVisibility(View.GONE);
        }
    }

    private void initializeApp() {
        setupMediaPipe();
        startCamera();
    }

    private void selectColor(int index) {
        selectedColorIndex = index;
        drawingView.setCurrentColor(colors[index]);
        updateColorSelection();
    }

    private void updateColorSelection() {
        for (int i = 0; i < colorViews.length; i++) {
            if (i == selectedColorIndex) {
                colorViews[i].setScaleX(1.3f);
                colorViews[i].setScaleY(1.3f);
                colorViews[i].setAlpha(1.0f);
            } else {
                colorViews[i].setScaleX(1.0f);
                colorViews[i].setScaleY(1.0f);
                colorViews[i].setAlpha(0.6f);
            }
        }
    }

    // ============ MEDIAPIPE ============

    private void setupMediaPipe() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .setDelegate(BaseOptions.Delegate.GPU)
                .build();

            HandLandmarkerOptions options = HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.7f)
                .setMinHandPresenceConfidence(0.7f)
                .setMinTrackingConfidence(0.6f)
                .setResultListener(this::onHandResults)
                .setErrorListener((error) -> {
                    Log.e(TAG, "MediaPipe error: " + error.message());
                })
                .build();

            handLandmarker = HandLandmarker.createFromOptions(this, options);
            Log.d(TAG, "MediaPipe initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MediaPipe", e);
            runOnUiThread(() -> statusText.setText("Ошибка MediaPipe!"));
        }
    }

    // ============ КАМЕРА ============

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                // Превью
                Preview preview = new Preview.Builder()
                    .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Зеркалим превью для фронтальной камеры
                previewView.setImplementationMode(
                    PreviewView.ImplementationMode.COMPATIBLE);
                previewView.setScaleType(
                    PreviewView.ScaleType.FILL_CENTER);

                // Анализ кадров
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(640, 480))
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build();

                analysis.setAnalyzer(cameraExecutor, this::processFrame);

                // Фронтальная камера
                CameraSelector selector =
                    CameraSelector.DEFAULT_FRONT_CAMERA;

                provider.unbindAll();
                provider.bindToLifecycle(
                    this, selector, preview, analysis);

                // Скрываем экран загрузки
                runOnUiThread(() -> {
                    loadingScreen.setVisibility(View.GONE);
                });

                Log.d(TAG, "Camera started");

            } catch (Exception e) {
                Log.e(TAG, "Camera start failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processFrame(@NonNull ImageProxy imageProxy) {
        if (handLandmarker == null) {
            imageProxy.close();
            return;
        }

        try {
            Bitmap bitmap = imageProxy.toBitmap();

            if (bitmap != null) {
                long timestamp = System.currentTimeMillis();

                // Гарантируем возрастающий timestamp
                if (timestamp <= lastTimestamp) {
                    timestamp = lastTimestamp + 1;
                }
                lastTimestamp = timestamp;

                MPImage mpImage = new BitmapImageBuilder(bitmap).build();
                handLandmarker.detectAsync(mpImage, timestamp);
            }
        } catch (Exception e) {
            Log.e(TAG, "Frame processing error", e);
        } finally {
            imageProxy.close();
        }
    }

    // ============ ОБРАБОТКА РЕЗУЛЬТАТОВ ============

    private void onHandResults(HandLandmarkerResult result, MPImage input) {
        runOnUiThread(() -> {
            try {
                processHandResults(result);
            } catch (Exception e) {
                Log.e(TAG, "Result processing error", e);
            }
        });
    }

    private void processHandResults(HandLandmarkerResult result) {
        // FPS
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 500) {
            int fps = (int)(frameCount / ((now - lastFpsTime) / 1000f));
            fpsText.setText(fps + " fps");
            frameCount = 0;
            lastFpsTime = now;
        }

        List<List<NormalizedLandmark>> hands = result.landmarks();
        int numHands = hands.size();

        // Обновляем индикаторы
        statusDot1.setBackgroundResource(
            numHands >= 1 ? R.drawable.status_dot_active
                          : R.drawable.status_dot_inactive);
        statusDot2.setBackgroundResource(
            numHands >= 2 ? R.drawable.status_dot_active
                          : R.drawable.status_dot_inactive);

        if (numHands == 0) {
            statusText.setText("Ожидание рук...");

            // Завершаем все активные действия
            if (isDrawing1) {
                drawingView.finishStroke();
                isDrawing1 = false;
            }
            if (drawingView.isGrabbing()) {
                drawingView.finishGrab();
            }

            confirmedGesture1 = "none";
            confirmedGesture2 = "none";
            drawingView.hideSkeletonAndCursor();
            return;
        }

        int viewW = drawingView.getWidth();
        int viewH = drawingView.getHeight();
        if (viewW == 0 || viewH == 0) return;

        // === РУКА 1 ===
        List<NormalizedLandmark> landmarks1 = hands.get(0);

        // Скелет
        float[][] skeleton = new float[21][2];
        for (int i = 0; i < 21; i++) {
            NormalizedLandmark lm = landmarks1.get(i);
            // Зеркалим X для фронтальной камеры
            skeleton[i][0] = (1f - lm.x()) * viewW;
            skeleton[i][1] = lm.y() * viewH;
        }
        drawingView.setSkeletonPoints(skeleton);

        // Жест
        String rawGesture1 = detectGesture(landmarks1);
        String gesture1 = stabilizeGesture1(rawGesture1);

        // Позиция указательного пальца
        NormalizedLandmark indexTip1 = landmarks1.get(8);
        NormalizedLandmark thumbTip1 = landmarks1.get(4);

        float rawX1, rawY1;
        if ("pinch".equals(gesture1)) {
            rawX1 = (1f - (indexTip1.x() + thumbTip1.x()) / 2f) * viewW;
            rawY1 = ((indexTip1.y() + thumbTip1.y()) / 2f) * viewH;
        } else {
            rawX1 = (1f - indexTip1.x()) * viewW;
            rawY1 = indexTip1.y() * viewH;
        }

        // Сглаживание
        smoothX1 = smoothX1 * SMOOTH_FACTOR + rawX1 * (1f - SMOOTH_FACTOR);
        smoothY1 = smoothY1 * SMOOTH_FACTOR + rawY1 * (1f - SMOOTH_FACTOR);

        drawingView.setCursorPosition(smoothX1, smoothY1);
        drawingView.setCursorVisible(true);

        // Обработка жеста
        StringBuilder mode = new StringBuilder();

        switch (gesture1) {
            case "point":
                drawingView.setCursorColor(drawingView.getCurrentColor());

                // Если был захват — завершаем
                if (drawingView.isGrabbing()) {
                    drawingView.finishGrab();
                }

                if (!isDrawing1) {
                    drawingView.startStroke(smoothX1, smoothY1);
                    isDrawing1 = true;
                } else {
                    drawingView.continueStroke(smoothX1, smoothY1);
                }
                mode.append("✏️ Рисование");
                break;

            case "pinch":
                drawingView.setCursorColor(Color.parseColor("#FFD700"));

                if (isDrawing1) {
                    drawingView.finishStroke();
                    isDrawing1 = false;
                }

                if (!drawingView.isGrabbing()) {
                    drawingView.startGrab(smoothX1, smoothY1);
                } else {
                    drawingView.continueGrab(smoothX1, smoothY1);
                }

                if (drawingView.isGrabbing()) {
                    mode.append("👌 Захват (")
                        .append(drawingView.getGrabbedCount())
                        .append(")");
                } else {
                    mode.append("👌 Щипок");
                }
                break;

            case "open":
                drawingView.setCursorColor(
                    Color.argb(100, 255, 255, 255));

                if (isDrawing1) {
                    drawingView.finishStroke();
                    isDrawing1 = false;
                }
                if (drawingView.isGrabbing()) {
                    drawingView.finishGrab();
                }

                mode.append("✋ Пауза");
                break;

            default:
                drawingView.setCursorColor(
                    Color.argb(80, 255, 255, 255));

                if (isDrawing1) {
                    drawingView.finishStroke();
                    isDrawing1 = false;
                }
                if (drawingView.isGrabbing()) {
                    drawingView.finishGrab();
                }

                mode.append("🤚 Готов");
                break;
        }

        // === РУКА 2 (если есть) ===
        if (numHands >= 2) {
            List<NormalizedLandmark> landmarks2 = hands.get(1);
            String rawGesture2 = detectGesture(landmarks2);
            String gesture2 = stabilizeGesture2(rawGesture2);

            mode.append(" | ");
            switch (gesture2) {
                case "point": mode.append("✏️"); break;
                case "pinch": mode.append("👌"); break;
                case "open": mode.append("✋"); break;
                default: mode.append("🤚"); break;
            }
        }

        statusText.setText(mode.toString());
        drawingView.invalidate();
    }

    // ============ РАСПОЗНАВАНИЕ ЖЕСТОВ ============

    private String detectGesture(List<NormalizedLandmark> lm) {
        boolean indexUp = lm.get(8).y() < lm.get(6).y() - 0.03f;
        boolean middleUp = lm.get(12).y() < lm.get(10).y() - 0.03f;
        boolean ringUp = lm.get(16).y() < lm.get(14).y() - 0.03f;
        boolean pinkyUp = lm.get(20).y() < lm.get(18).y() - 0.03f;

        float pinchDist = (float) Math.hypot(
            lm.get(4).x() - lm.get(8).x(),
            lm.get(4).y() - lm.get(8).y()
        );

        if (pinchDist < PINCH_THRESHOLD) return "pinch";
        if (indexUp && !middleUp && !ringUp && !pinkyUp) return "point";
        if (indexUp && middleUp && ringUp && pinkyUp) return "open";
        return "none";
    }

    private String stabilizeGesture1(String detected) {
        if (detected.equals(pendingGesture1)) {
            gestureFrames1++;
        } else {
            pendingGesture1 = detected;
            gestureFrames1 = 1;
        }

        if (gestureFrames1 >= GESTURE_THRESHOLD
                && !confirmedGesture1.equals(pendingGesture1)) {
            confirmedGesture1 = pendingGesture1;
        }
        return confirmedGesture1;
    }

    private String stabilizeGesture2(String detected) {
        if (detected.equals(pendingGesture2)) {
            gestureFrames2++;
        } else {
            pendingGesture2 = detected;
            gestureFrames2 = 1;
        }

        if (gestureFrames2 >= GESTURE_THRESHOLD
                && !confirmedGesture2.equals(pendingGesture2)) {
            confirmedGesture2 = pendingGesture2;
        }
        return confirmedGesture2;
    }

    // ============ ЖИЗНЕННЫЙ ЦИКЛ ============

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (handLandmarker != null) {
            handLandmarker.close();
        }
    }
}
