package com.handdraw.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class DrawingView extends View {

    // Список завершённых штрихов
    private final List<Stroke> strokes = new ArrayList<>();

    // Текущий штрих (пока рисуется)
    private Stroke currentStroke = null;

    // Настройки рисования
    private int currentColor = Color.parseColor("#ff4757");
    private float strokeWidth = 8f;
    private final float[] thicknesses = {6f, 12f, 20f, 32f};
    private int thicknessIdx = 0;

    // Курсор
    private float cursorX = 0f;
    private float cursorY = 0f;
    private boolean cursorVisible = false;

    // Кисти
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Скелет руки
    private float[][] skeletonPoints = null;
    private boolean skeletonVisible = false;
    private final Paint skeletonLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skeletonDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skeletonTipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Соединения скелета
    private static final int[][] CONNECTIONS = {
        {0,1},{1,2},{2,3},{3,4},
        {0,5},{5,6},{6,7},{7,8},
        {0,9},{9,10},{10,11},{11,12},
        {0,13},{13,14},{14,15},{15,16},
        {0,17},{17,18},{18,19},{19,20},
        {5,9},{9,13},{13,17}
    };

    private static final int[] TIPS = {4, 8, 12, 16, 20};

    // Захват (перетаскивание)
    private boolean isGrabbing = false;
    private List<Integer> grabbedIndices = new ArrayList<>();
    private PointF lastGrabPos = null;
    private final Paint grabHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DrawingView(Context context) {
        super(context);
        init();
    }

    public DrawingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DrawingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setColor(Color.WHITE);
        cursorPaint.setStrokeWidth(2f);

        cursorFillPaint.setStyle(Paint.Style.FILL);
        cursorFillPaint.setColor(Color.argb(40, 255, 255, 255));

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);

        skeletonLinePaint.setStyle(Paint.Style.STROKE);
        skeletonLinePaint.setColor(Color.argb(150, 0, 255, 200));
        skeletonLinePaint.setStrokeWidth(2f);

        skeletonDotPaint.setStyle(Paint.Style.FILL);
        skeletonDotPaint.setColor(Color.argb(200, 0, 255, 200));

        skeletonTipPaint.setStyle(Paint.Style.FILL);
        skeletonTipPaint.setColor(Color.argb(255, 0, 255, 200));

        grabHighlightPaint.setStyle(Paint.Style.STROKE);
        grabHighlightPaint.setColor(Color.parseColor("#FFD700"));
        grabHighlightPaint.setStrokeWidth(2f);
        grabHighlightPaint.setPathEffect(
            new android.graphics.DashPathEffect(new float[]{16f, 8f}, 0f));
    }

    // ============ РИСОВАНИЕ ШТРИХОВ ============

    public void startStroke(float x, float y) {
        currentStroke = new Stroke(currentColor, strokeWidth);
        currentStroke.points.add(new PointF(x, y));
        invalidate();
    }

    public void continueStroke(float x, float y) {
        if (currentStroke != null) {
            currentStroke.points.add(new PointF(x, y));
            invalidate();
        }
    }

    public void finishStroke() {
        if (currentStroke != null && currentStroke.points.size() >= 2) {
            strokes.add(currentStroke);
        }
        currentStroke = null;
        invalidate();
    }

    // ============ ЗАХВАТ / ПЕРЕТАСКИВАНИЕ ============

    public void startGrab(float x, float y) {
        grabbedIndices = findNearbyStrokes(x, y, 80f);
        if (!grabbedIndices.isEmpty()) {
            isGrabbing = true;
            lastGrabPos = new PointF(x, y);
        }
    }

    public void continueGrab(float x, float y) {
        if (isGrabbing && lastGrabPos != null) {
            float dx = x - lastGrabPos.x;
            float dy = y - lastGrabPos.y;

            for (int idx : grabbedIndices) {
                if (idx < strokes.size()) {
                    Stroke s = strokes.get(idx);
                    s.offsetX += dx;
                    s.offsetY += dy;
                }
            }

            lastGrabPos.set(x, y);
            invalidate();
        }
    }

    public void finishGrab() {
        if (isGrabbing) {
            // Применяем смещения к точкам
            for (int idx : grabbedIndices) {
                if (idx < strokes.size()) {
                    Stroke s = strokes.get(idx);
                    for (PointF p : s.points) {
                        p.x += s.offsetX;
                        p.y += s.offsetY;
                    }
                    s.offsetX = 0;
                    s.offsetY = 0;
                }
            }
        }
        isGrabbing = false;
        grabbedIndices.clear();
        lastGrabPos = null;
        invalidate();
    }

    public boolean isGrabbing() {
        return isGrabbing;
    }

    public int getGrabbedCount() {
        return grabbedIndices.size();
    }

    private List<Integer> findNearbyStrokes(float x, float y, float radius) {
        List<Integer> found = new ArrayList<>();
        float r2 = radius * radius;

        for (int i = 0; i < strokes.size(); i++) {
            Stroke s = strokes.get(i);
            for (PointF p : s.points) {
                float dx = (p.x + s.offsetX) - x;
                float dy = (p.y + s.offsetY) - y;
                if (dx * dx + dy * dy < r2) {
                    found.add(i);
                    break;
                }
            }
        }
        return found;
    }

    // ============ УПРАВЛЕНИЕ ============

    public void clearAll() {
        strokes.clear();
        currentStroke = null;
        isGrabbing = false;
        grabbedIndices.clear();
        lastGrabPos = null;
        invalidate();
    }

    public void undo() {
        if (!strokes.isEmpty()) {
            strokes.remove(strokes.size() - 1);
            invalidate();
        }
    }

    public void nextThickness() {
        thicknessIdx = (thicknessIdx + 1) % thicknesses.length;
        strokeWidth = thicknesses[thicknessIdx];
    }

    public float getStrokeWidth() {
        return strokeWidth;
    }

    public void setCurrentColor(int color) {
        this.currentColor = color;
    }

    public int getCurrentColor() {
        return currentColor;
    }

    // ============ КУРСОР ============

    public void setCursorPosition(float x, float y) {
        this.cursorX = x;
        this.cursorY = y;
    }

    public void setCursorVisible(boolean visible) {
        this.cursorVisible = visible;
    }

    public void setCursorColor(int color) {
        cursorPaint.setColor(color);
        cursorFillPaint.setColor(Color.argb(40,
            Color.red(color), Color.green(color), Color.blue(color)));
    }

    // ============ СКЕЛЕТ РУКИ ============

    public void setSkeletonPoints(float[][] points) {
        this.skeletonPoints = points;
        this.skeletonVisible = (points != null);
        invalidate();
    }

    public void hideSkeletonAndCursor() {
        this.skeletonVisible = false;
        this.skeletonPoints = null;
        this.cursorVisible = false;
        invalidate();
    }

    // ============ ОТРИСОВКА ============

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Рисуем завершённые штрихи
        for (Stroke s : strokes) {
            drawStroke(canvas, s);
        }

        // Текущий штрих
        if (currentStroke != null && currentStroke.points.size() >= 2) {
            drawStroke(canvas, currentStroke);
        }

        // Подсветка захваченных штрихов
        if (isGrabbing && !grabbedIndices.isEmpty()) {
            drawGrabHighlight(canvas);
        }

        // Скелет руки
        if (skeletonVisible && skeletonPoints != null && skeletonPoints.length == 21) {
            drawSkeleton(canvas);
        }

        // Курсор
        if (cursorVisible) {
            float radius = strokeWidth + 12f;
            canvas.drawCircle(cursorX, cursorY, radius, cursorFillPaint);
            canvas.drawCircle(cursorX, cursorY, radius, cursorPaint);
        }
    }

    private void drawStroke(Canvas canvas, Stroke stroke) {
        if (stroke.points.size() < 2) return;

        float ox = stroke.offsetX;
        float oy = stroke.offsetY;

        // Свечение
        glowPaint.setColor(stroke.color);
        glowPaint.setStrokeWidth(stroke.width + 6f);
        glowPaint.setAlpha(60);
        glowPaint.setMaskFilter(
            new android.graphics.BlurMaskFilter(stroke.width * 1.5f,
                android.graphics.BlurMaskFilter.Blur.NORMAL));

        Path glowPath = buildPath(stroke, ox, oy);
        canvas.drawPath(glowPath, glowPaint);

        // Основная линия
        strokePaint.setColor(stroke.color);
        strokePaint.setStrokeWidth(stroke.width);
        strokePaint.setAlpha(255);

        Path mainPath = buildPath(stroke, ox, oy);
        canvas.drawPath(mainPath, strokePaint);
    }

    private Path buildPath(Stroke stroke, float ox, float oy) {
        Path path = new Path();
        PointF first = stroke.points.get(0);
        path.moveTo(first.x + ox, first.y + oy);

        for (int i = 1; i < stroke.points.size(); i++) {
            PointF p0 = stroke.points.get(i - 1);
            PointF p1 = stroke.points.get(i);
            float mx = (p0.x + p1.x) / 2f + ox;
            float my = (p0.y + p1.y) / 2f + oy;
            path.quadTo(p0.x + ox, p0.y + oy, mx, my);
        }

        PointF last = stroke.points.get(stroke.points.size() - 1);
        path.lineTo(last.x + ox, last.y + oy);

        return path;
    }

    private void drawSkeleton(Canvas canvas) {
        // Линии
        for (int[] conn : CONNECTIONS) {
            float[] a = skeletonPoints[conn[0]];
            float[] b = skeletonPoints[conn[1]];
            canvas.drawLine(a[0], a[1], b[0], b[1], skeletonLinePaint);
        }

        // Точки
        for (int i = 0; i < 21; i++) {
            float[] p = skeletonPoints[i];
            boolean isTip = false;
            for (int t : TIPS) {
                if (t == i) { isTip = true; break; }
            }

            if (isTip) {
                canvas.drawCircle(p[0], p[1], 7f, skeletonTipPaint);
            } else {
                canvas.drawCircle(p[0], p[1], 4f, skeletonDotPaint);
            }
        }
    }

    private void drawGrabHighlight(Canvas canvas) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;

        for (int idx : grabbedIndices) {
            if (idx >= strokes.size()) continue;
            Stroke s = strokes.get(idx);
            for (PointF p : s.points) {
                float px = p.x + s.offsetX;
                float py = p.y + s.offsetY;
                if (px < minX) minX = px;
                if (py < minY) minY = py;
                if (px > maxX) maxX = px;
                if (py > maxY) maxY = py;
            }
        }

        float pad = 20f;
        canvas.drawRect(minX - pad, minY - pad,
            maxX + pad, maxY + pad, grabHighlightPaint);
    }

    // ============ ВНУТРЕННИЙ КЛАСС ============

    static class Stroke {
        int color;
        float width;
        float offsetX = 0f;
        float offsetY = 0f;
        List<PointF> points = new ArrayList<>();

        Stroke(int color, float width) {
            this.color = color;
            this.width = width;
        }
    }
}
