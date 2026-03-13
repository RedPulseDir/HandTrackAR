package com.handdraw.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
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
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HandDrawAR";
    private static final int CAMERA_PERMISSION_CODE = 100;

    private PreviewView previewView;
    private DrawingView drawingView;
    private TextView statusText;
    private TextView fpsText;
    private FrameLayout loadingScreen;
    private View statusDot1;
    private View statusDot2;

    private HandLandmarker handLandmarker;
    private ExecutorService cameraExecutor;

    // Защита от двойной отправки кадров
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    private int frameCount = 0;
    private long lastFpsTime = System.currentTimeMillis();

    // Жесты рука 1
    private String confirmedGesture1 = "none";
    private String pendingGesture1 = "none";
    private int gestureFrames1 = 0;
    private boolean isDrawing1 = false;
    private float smoothX1 = -1f;
    private float smoothY1 = -1f;

    // Жесты рука 2
    private String confirmedGesture2 = "none";
    private String pendingGesture2 = "none";
    private int gestureFrames2 = 0;

    private static final float SMOOTH_FACTOR = 0.4f;
    private static final float PINCH_THRESHOLD = 0.065f;
    private static final int GESTURE_THRESHOLD = 2;

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

    private long lastTimestamp = 0;

    // Размеры входного изображения для маппинга координат
    private int imageWidth = 640;
    private int imageHeight = 480;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        setContentView(R.layout.activity_main);

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
        btnThickness.setOnClickListener(v -> drawingView.nextThickness());

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            initializeApp();
        } else {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    private void hideSystemUI() {
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
                .build();

            HandLandmarker.HandLandmarkerOptions options =
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.6f)
                    .setMinHandPresenceConfidence(0.6f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener(this::onHandResults)
                    .setErrorListener((error) -> {
                        Log.e(TAG, "MediaPipe error: " + error.getMessage());
                    })
                    .build();

            handLandmarker = HandLandmarker.createFromOptions(this, options);
            Log.d(TAG, "MediaPipe initialized");

        } catch (Exception e) {
            Log.e(TAG, "MediaPipe init failed", e);
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

                Preview preview = new Preview.Builder()
                    .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                previewView.setImplementationMode(
                    PreviewView.ImplementationMode.COMPATIBLE);
                previewView.setScaleType(
                    PreviewView.ScaleType.FILL_CENTER);

                // Уменьшаем разрешение для скорости
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new android.util.Size(320, 240))
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build();

                analysis.setAnalyzer(cameraExecutor, this::processFrame);

                CameraSelector selector =
                    CameraSelector.DEFAULT_FRONT_CAMERA;

                provider.unbindAll();
                provider.bindToLifecycle(
                    this, selector, preview, analysis);

                runOnUiThread(() -> loadingScreen.setVisibility(View.GONE));
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

        // Пропускаем кадр если предыдущий ещё обрабатывается
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        try {
            Bitmap bitmap = imageProxy.toBitmap();

            if (bitmap != null) {
                imageWidth = bitmap.getWidth();
                imageHeight = bitmap.getHeight();

                long timestamp = System.currentTimeMillis();
                if (timestamp <= lastTimestamp) {
                    timestamp = lastTimestamp + 1;
                }
                lastTimestamp = timestamp;

                MPImage mpImage = new BitmapImageBuilder(bitmap).build();
                handLandmarker.detectAsync(mpImage, timestamp);
            } else {
                isProcessing.set(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Frame error", e);
            isProcessing.set(false);
        } finally {
            imageProxy.close();
        }
    }

    // ============ РЕЗУЛЬТАТЫ ============

    private void onHandResults(HandLandmarkerResult result, MPImage input) {
        isProcessing.set(false);

        runOnUiThread(() -> {
            try {
                processHandResults(result);
            } catch (Exception e) {
                Log.e(TAG, "Result error", e);
            }
        });
    }

    private void processHandResults(HandLandmarkerResult result) {
        // FPS
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            int fps = (int)(frameCount / ((now - lastFpsTime) / 1000f));
            fpsText.setText(fps + " fps");
            frameCount = 0;
            lastFpsTime = now;
        }

        List<List<NormalizedLandmark>> hands = result.landmarks();
        int numHands = hands.size();

        statusDot1.setBackgroundResource(
            numHands >= 1 ? R.drawable.status_dot_active
                          : R.drawable.status_dot_inactive);
        statusDot2.setBackgroundResource(
            numHands >= 2 ? R.drawable.status_dot_active
                          : R.drawable.status_dot_inactive);

        if (numHands == 0) {
            statusText.setText("Ожидание...");
            if (isDrawing1) {
                drawingView.finishStroke();
                isDrawing1 = false;
            }
            if (drawingView.isGrabbing()) {
                drawingView.finishGrab();
            }
            confirmedGesture1 = "none";
            drawingView.hideSkeletonAndCursor();
            return;
        }

        int viewW = drawingView.getWidth();
        int viewH = drawingView.getHeight();
        if (viewW == 0 || viewH == 0) return;

        List<NormalizedLandmark> landmarks = hands.get(0);

        // ===== ПРАВИЛЬНЫЙ МАППИНГ КООРДИНАТ =====
        // Фронтальная камера: PreviewView уже зеркалит картинку
        // MediaPipe даёт координаты незеркалённого изображения
        // Нужно зеркалить X чтобы совпало с превью

        // Вычисляем как PreviewView масштабирует (FILL_CENTER)
        float videoAspect = (float) imageWidth / imageHeight;
        float screenAspect = (float) viewW / viewH;

        float scaleX, scaleY, offsetX, offsetY;

        if (screenAspect > videoAspect) {
            // Экран шире — подгоняем по ширине, обрезаем верх/низ
            scaleX = viewW;
            scaleY = viewW / videoAspect;
            offsetX = 0;
            offsetY = (viewH - scaleY) / 2f;
        } else {
            // Экран уже — подгоняем по высоте, обрезаем бока
            scaleX = viewH * videoAspect;
            scaleY = viewH;
            offsetX = (viewW - scaleX) / 2f;
            offsetY = 0;
        }

        // Строим скелет с правильными координатами
        float[][] skeleton = new float[21][2];
        for (int i = 0; i < 21; i++) {
            NormalizedLandmark lm = landmarks.get(i);
            // Зеркалим X (1 - x) для совпадения с превью фронтальной камеры
            float sx = (1f - lm.x()) * scaleX + offsetX;
            float sy = lm.y() * scaleY + offsetY;
            skeleton[i][0] = sx;
            skeleton[i][1] = sy;
        }
        drawingView.setSkeletonPoints(skeleton);

        // Жест
        String rawGesture = detectGesture(landmarks);
        String gesture = stabilizeGesture1(rawGesture);

        // Позиция кончика указательного пальца
        float tipX = skeleton[8][0];
        float tipY = skeleton[8][1];

        // Для щипка — середина между большим и указательным
        if ("pinch".equals(gesture)) {
            tipX = (skeleton[4][0] + skeleton[8][0]) / 2f;
            tipY = (skeleton[4][1] + skeleton[8][1]) / 2f;
        }

        // Сглаживание
        if (smoothX1 < 0) {
            smoothX1 = tipX;
            smoothY1 = tipY;
        } else {
            smoothX1 = smoothX1 * SMOOTH_FACTOR + tipX * (1f - SMOOTH_FACTOR);
            smoothY1 = smoothY1 * SMOOTH_FACTOR + tipY * (1f - SMOOTH_FACTOR);
        }

        drawingView.setCursorPosition(smoothX1, smoothY1);
        drawingView.setCursorVisible(true);

        // Обработка жестов
        String modeStr;

        switch (gesture) {
            case "point":
                drawingView.setCursorColor(drawingView.getCurrentColor());
                if (drawingView.isGrabbing()) {
                    drawingView.finishGrab();
                }
                if (!isDrawing1) {
                    drawingView.startStroke(smoothX1, smoothY1);
                    isDrawing1 = true;
                } else {
                    drawingView.continueStroke(smoothX1, smoothY1);
                }
                modeStr = "Рисование";
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
                modeStr = drawingView.isGrabbing()
                    ? "Захват (" + drawingView.getGrabbedCount() + ")"
                    : "Щипок";
                break;

            case "open":
                drawingView.setCursorColor(Color.argb(100, 255, 255, 255));
                if (isDrawing1) {
                    drawingView.finishStroke();
                    isDrawing1 = false;
                }
                if (drawingView.isGrabbing()) {
                    drawingView.finishGrab();
                }
                modeStr = "Пауза";
                break;

            default:
                drawingView.setCursorColor(Color.argb(80, 255, 255, 255));
                if (isDrawing1) {
                    drawingView.finishStroke();
                    isDrawing1 = false;
                }
                if (drawingView.isGrabbing()) {
                    drawingView.finishGrab();
                }
                modeStr = "Готов";
                break;
        }

        statusText.setText(modeStr);
        drawingView.invalidate();
    }

    // ============ ЖЕСТЫ ============

    private String detectGesture(List<NormalizedLandmark> lm) {
        // Палец поднят если кончик ВЫШЕ (меньше Y) чем PIP сустав
        boolean indexUp = lm.get(8).y() < lm.get(6).y() - 0.02f;
        boolean middleUp = lm.get(12).y() < lm.get(10).y() - 0.02f;
        boolean ringUp = lm.get(16).y() < lm.get(14).y() - 0.02f;
        boolean pinkyUp = lm.get(20).y() < lm.get(18).y() - 0.02f;

        // Расстояние между большим и указательным
        float dx = lm.get(4).x() - lm.get(8).x();
        float dy = lm.get(4).y() - lm.get(8).y();
        float pinchDist = (float) Math.sqrt(dx * dx + dy * dy);

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

    // ============ LIFECYCLE ============

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
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
