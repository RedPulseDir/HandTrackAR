package com.handdraw.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class DrawingView extends View {

    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke currentStroke = null;

    private int currentColor = Color.parseColor("#ff4757");
    private float strokeWidth = 8f;
    private final float[] thicknesses = {6f, 12f, 20f, 32f};
    private int thicknessIdx = 0;

    private float cursorX = 0f;
    private float cursorY = 0f;
    private boolean cursorVisible = false;
    private int cursorColor = Color.WHITE;

    // Скелет
    private float[][] skeletonPoints = null;
    private boolean skeletonVisible = false;

    // Захват
    private boolean isGrabbing = false;
    private final List<Integer> grabbedIndices = new ArrayList<>();
    private PointF lastGrabPos = null;

    // Paint объекты — создаём один раз
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skeletonLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skeletonDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skeletonTipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grabPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Переиспользуемый Path
    private final Path tempPath = new Path();

    private static final int[][] CONNECTIONS = {
        {0,1},{1,2},{2,3},{3,4},
        {0,5},{5,6},{6,7},{7,8},
        {0,9},{9,10},{10,11},{11,12},
        {0,13},{13,14},{14,15},{15,16},
        {0,17},{17,18},{18,19},{19,20},
        {5,9},{9,13},{13,17}
    };

    private static final int[] TIPS = {4, 8, 12, 16, 20};

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
        // Аппаратное ускорение для плавности
        setLayerType(LAYER_TYPE_HARDWARE, null);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);

        cursorStrokePaint.setStyle(Paint.Style.STROKE);
        cursorStrokePaint.setStrokeWidth(2f);

        cursorFillPaint.setStyle(Paint.Style.FILL);

        skeletonLinePaint.setStyle(Paint.Style.STROKE);
        skeletonLinePaint.setStrokeWidth(2f);
        skeletonLinePaint.setColor(Color.argb(120, 0, 255, 200));

        skeletonDotPaint.setStyle(Paint.Style.FILL);
        skeletonDotPaint.setColor(Color.argb(180, 0, 255, 200));

        skeletonTipPaint.setStyle(Paint.Style.FILL);
        skeletonTipPaint.setColor(Color.argb(255, 0, 255, 200));

        grabPaint.setStyle(Paint.Style.STROKE);
        grabPaint.setColor(Color.parseColor("#FFD700"));
        grabPaint.setStrokeWidth(2f);
        grabPaint.setPathEffect(
            new android.graphics.DashPathEffect(new float[]{16f, 8f}, 0f));
    }

    // ============ РИСОВАНИЕ ============

    public void startStroke(float x, float y) {
        currentStroke = new Stroke(currentColor, strokeWidth);
        currentStroke.points.add(new PointF(x, y));
    }

    public void continueStroke(float x, float y) {
        if (currentStroke != null) {
            // Не добавляем точку если она слишком близко к предыдущей
            List<PointF> pts = currentStroke.points;
            if (!pts.isEmpty()) {
                PointF last = pts.get(pts.size() - 1);
                float dx = x - last.x;
                float dy = y - last.y;
                if (dx * dx + dy * dy < 4f) return; // минимум 2px
            }
            pts.add(new PointF(x, y));
        }
    }

    public void finishStroke() {
        if (currentStroke != null && currentStroke.points.size() >= 2) {
            strokes.add(currentStroke);
        }
        currentStroke = null;
        invalidate();
    }

    // ============ ЗАХВАТ ============

    public void startGrab(float x, float y) {
        List<Integer> found = findNearbyStrokes(x, y, 80f);
        if (!found.isEmpty()) {
            grabbedIndices.clear();
            grabbedIndices.addAll(found);
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

    public boolean isGrabbing() { return isGrabbing; }
    public int getGrabbedCount() { return grabbedIndices.size(); }

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

    public float getStrokeWidth() { return strokeWidth; }
    public void setCurrentColor(int color) { this.currentColor = color; }
    public int getCurrentColor() { return currentColor; }

    public void setCursorPosition(float x, float y) {
        this.cursorX = x;
        this.cursorY = y;
    }

    public void setCursorVisible(boolean v) { this.cursorVisible = v; }

    public void setCursorColor(int color) {
        this.cursorColor = color;
    }

    public void setSkeletonPoints(float[][] points) {
        this.skeletonPoints = points;
        this.skeletonVisible = (points != null && points.length == 21);
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
        // Завершённые штрихи
        for (int i = 0; i < strokes.size(); i++) {
            drawStroke(canvas, strokes.get(i));
        }

        // Текущий штрих
        if (currentStroke != null && currentStroke.points.size() >= 2) {
            drawStroke(canvas, currentStroke);
        }

        // Подсветка захвата
        if (isGrabbing && !grabbedIndices.isEmpty()) {
            drawGrabHighlight(canvas);
        }

        // Скелет
        if (skeletonVisible && skeletonPoints != null) {
            drawSkeleton(canvas);
        }

        // Курсор
        if (cursorVisible) {
            float r = strokeWidth + 12f;
            cursorFillPaint.setColor(Color.argb(30,
                Color.red(cursorColor),
                Color.green(cursorColor),
                Color.blue(cursorColor)));
            canvas.drawCircle(cursorX, cursorY, r, cursorFillPaint);

            cursorStrokePaint.setColor(cursorColor);
            canvas.drawCircle(cursorX, cursorY, r, cursorStrokePaint);

            // Маленькая точка в центре
            cursorFillPaint.setColor(cursorColor);
            canvas.drawCircle(cursorX, cursorY, 3f, cursorFillPaint);
        }
    }

    private void drawStroke(Canvas canvas, Stroke stroke) {
        if (stroke.points.size() < 2) return;

        float ox = stroke.offsetX;
        float oy = stroke.offsetY;

        buildPath(tempPath, stroke, ox, oy);

        // Свечение
        glowPaint.setColor(stroke.color);
        glowPaint.setStrokeWidth(stroke.width + 4f);
        glowPaint.setAlpha(40);
        canvas.drawPath(tempPath, glowPaint);

        // Основная линия
        strokePaint.setColor(stroke.color);
        strokePaint.setStrokeWidth(stroke.width);
        strokePaint.setAlpha(255);
        canvas.drawPath(tempPath, strokePaint);
    }

    private void buildPath(Path path, Stroke stroke, float ox, float oy) {
        path.reset();
        List<PointF> pts = stroke.points;

        path.moveTo(pts.get(0).x + ox, pts.get(0).y + oy);

        if (pts.size() == 2) {
            path.lineTo(pts.get(1).x + ox, pts.get(1).y + oy);
            return;
        }

        for (int i = 1; i < pts.size(); i++) {
            PointF prev = pts.get(i - 1);
            PointF curr = pts.get(i);
            float mx = (prev.x + curr.x) / 2f + ox;
            float my = (prev.y + curr.y) / 2f + oy;
            path.quadTo(prev.x + ox, prev.y + oy, mx, my);
        }

        PointF last = pts.get(pts.size() - 1);
        path.lineTo(last.x + ox, last.y + oy);
    }

    private void drawSkeleton(Canvas canvas) {
        for (int[] c : CONNECTIONS) {
            float[] a = skeletonPoints[c[0]];
            float[] b = skeletonPoints[c[1]];
            canvas.drawLine(a[0], a[1], b[0], b[1], skeletonLinePaint);
        }

        for (int i = 0; i < 21; i++) {
            float[] p = skeletonPoints[i];
            boolean isTip = false;
            for (int t : TIPS) {
                if (t == i) { isTip = true; break; }
            }
            if (isTip) {
                canvas.drawCircle(p[0], p[1], 6f, skeletonTipPaint);
            } else {
                canvas.drawCircle(p[0], p[1], 3f, skeletonDotPaint);
            }
        }
    }

    private void drawGrabHighlight(Canvas canvas) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

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

        if (minX < Float.MAX_VALUE) {
            float pad = 20f;
            canvas.drawRect(minX - pad, minY - pad,
                maxX + pad, maxY + pad, grabPaint);
        }
    }

    // ============ STROKE ============

    static class Stroke {
        int color;
        float width;
        float offsetX = 0f;
        float offsetY = 0f;
        final List<PointF> points = new ArrayList<>();

        Stroke(int color, float width) {
            this.color = color;
            this.width = width;
        }
    }
    }
