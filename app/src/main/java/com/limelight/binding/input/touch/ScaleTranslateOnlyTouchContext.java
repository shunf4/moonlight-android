package com.limelight.binding.input.touch;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Pair;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@TargetApi(Build.VERSION_CODES.N)
public class ScaleTranslateOnlyTouchContext implements TouchContext {
    private int lastTouchDownX = 0;
    private int lastTouchDownY = 0;
    private int lastTouchDownXRel = 0;
    private int lastTouchDownYRel = 0;
    private long lastTouchDownTime = 0;
    private int lastTouchUpX = 0;
    private int lastTouchUpY = 0;
    private int lastMouseLeftClickX = 0;
    private int lastMouseLeftClickY = 0;
    private long lastTouchUpTime = 0;
    private int lastTouchLocationX = 0;
    private int lastTouchLocationY = 0;
    private int lastTouchLocationXRel = 0;
    private int lastTouchLocationYRel = 0;
    private boolean cancelled;

    private int pointerCount;
    private int maxPointerCountInGesture;

    private Supplier<Boolean> confirmedScaleTranslateGetter;
    private Consumer<Boolean> confirmedScaleTranslateSetter;
    private Supplier<Double> doubleFingerInitialSpacingGetter;
    private Consumer<Double> doubleFingerInitialSpacingSetter;
    private Supplier<Integer> doubleFingerInitialMidpointXGetter;
    private Consumer<Integer> doubleFingerInitialMidpointXSetter;
    private Supplier<Integer> doubleFingerInitialMidpointYGetter;
    private Consumer<Integer> doubleFingerInitialMidpointYSetter;
    private final Function<Integer, Pair<Integer, Integer>> otherTouchPosGetter;
    private final ScaleTransformCallback scaleTransformCallback;
    private boolean isPinchZoomTimedOut;
    private boolean hasEverScrolled;
    private boolean confirmedLongPressRightClick;
    private boolean confirmedLongPressHold;
    private boolean confirmedMouseLeftButtonDown;
    private boolean shouldDoubleClickDragTranslate;
    private final boolean modeLongPressNeededToDrag;
    private final boolean absoluteTouchTapOnlyPlacesMouse;
    private final Vibrator vibrator;

    private final NvConnection conn;
    private final int actionIndex;
    private final View targetView;
    private final Handler handler;

    private final int outerScreenWidth;
    private final int outerScreenHeight;

    private final int edgeSingleFingerScrollWidth;

    private boolean leftButtonAlreadyUp;

    private static final int SCROLL_SPEED_FACTOR = 3;

    private static final int LONG_PRESS_DISTANCE_THRESHOLD = 30;

    private static final int DOUBLE_TAP_TIME_THRESHOLD = 250;
    private static final int DOUBLE_TAP_DISTANCE_THRESHOLD = 60;

    private static final int TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD = 100;
    private static final int TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD = 20;

    public ScaleTranslateOnlyTouchContext(NvConnection conn, int actionIndex, View view,
                                          int outerScreenWidth, int outerScreenHeight,
                                          boolean modeLongPressNeededToDrag,
                                          int edgeSingleFingerScrollWidth,
                                          boolean shouldDoubleClickDragTranslate,
                                          boolean absoluteTouchTapOnlyPlacesMouse,
                                          Vibrator vibrator,
                                          Function<Integer, Pair<Integer, Integer>> otherTouchPosGetter,
                                          ScaleTransformCallback scaleTransformCallback,
                                          Supplier<Boolean> confirmedScaleTranslateGetter,
                                          Consumer<Boolean> confirmedScaleTranslateSetter,
                                          Supplier<Double> doubleFingerInitialSpacingGetter,
                                          Consumer<Double> doubleFingerInitialSpacingSetter,
                                          Supplier<Integer> doubleFingerInitialMidpointXGetter,
                                          Consumer<Integer> doubleFingerInitialMidpointXSetter,
                                          Supplier<Integer> doubleFingerInitialMidpointYGetter,
                                          Consumer<Integer> doubleFingerInitialMidpointYSetter
                                )
    {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.targetView = view;
        this.handler = new Handler(Looper.getMainLooper());

        this.outerScreenWidth = outerScreenWidth;
        this.outerScreenHeight = outerScreenHeight;

        this.modeLongPressNeededToDrag = modeLongPressNeededToDrag;
        this.edgeSingleFingerScrollWidth = edgeSingleFingerScrollWidth;
        this.shouldDoubleClickDragTranslate = shouldDoubleClickDragTranslate;
        this.absoluteTouchTapOnlyPlacesMouse = absoluteTouchTapOnlyPlacesMouse;
        this.vibrator = vibrator;

        this.otherTouchPosGetter = otherTouchPosGetter;
        this.scaleTransformCallback = scaleTransformCallback;
        this.confirmedScaleTranslateGetter = confirmedScaleTranslateGetter;
        this.confirmedScaleTranslateSetter = confirmedScaleTranslateSetter;
        this.doubleFingerInitialSpacingGetter = doubleFingerInitialSpacingGetter;
        this.doubleFingerInitialSpacingSetter = doubleFingerInitialSpacingSetter;
        this.doubleFingerInitialMidpointXGetter = doubleFingerInitialMidpointXGetter;
        this.doubleFingerInitialMidpointXSetter = doubleFingerInitialMidpointXSetter;
        this.doubleFingerInitialMidpointYGetter = doubleFingerInitialMidpointYGetter;
        this.doubleFingerInitialMidpointYSetter = doubleFingerInitialMidpointYSetter;
    }

    @Override
    public int getActionIndex()
    {
        return actionIndex;
    }

    @Override
    public int getLastTouchX() {
        return lastTouchLocationX;
    }

    @Override
    public int getLastTouchY() {
        return lastTouchLocationY;
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, int xRel, int yRel, long eventTime, boolean isNewFinger)
    {
        if (!isNewFinger) {
            // We don't handle finger transitions for absolute mode
            return true;
        }

        maxPointerCountInGesture = pointerCount;

        lastTouchLocationX = lastTouchDownX = eventX;
        lastTouchLocationXRel = lastTouchDownXRel = xRel;
        lastTouchLocationY = lastTouchDownY = eventY;
        lastTouchLocationYRel = lastTouchDownYRel = yRel;
        lastTouchDownTime = eventTime;
        cancelled = confirmedMouseLeftButtonDown = confirmedLongPressRightClick = confirmedLongPressHold = hasEverScrolled = false;

        confirmedScaleTranslateSetter.accept(false);
        doubleFingerInitialSpacingSetter.accept(Double.NaN);
        isPinchZoomTimedOut = false;

        // track pinch/zoom gesture in actionIndex=1 (second touch point)
        if (actionIndex == 1 && pointerCount == 2) {
            Pair<Integer, Integer> touch0Pos = otherTouchPosGetter.apply(0);
            int touch0X = touch0Pos.first;
            int touch0Y = touch0Pos.second;
            double s = Math.sqrt(Math.pow(eventX - touch0X, 2) + Math.pow(eventY - touch0Y, 2));
            if (s < 5.0d) {
                s = 5.0d;
            }
            doubleFingerInitialSpacingSetter.accept(s);
            doubleFingerInitialMidpointXSetter.accept((touch0X + eventX) / 2);
            doubleFingerInitialMidpointYSetter.accept((touch0Y + eventY) / 2);
            isPinchZoomTimedOut = false;
        }

        return true;
    }

    private boolean distanceExceeds(int deltaX, int deltaY, double limit) {
        return Math.sqrt(Math.pow(deltaX, 2) + Math.pow(deltaY, 2)) > limit;
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, int xRel, int yRel, long eventTime)
    {
        if (cancelled) {
            return;
        }

        // shunf4: this way of managing TouchContext (esp. using actionIndex instead of pointerId for indexing) makes it rather difficult for gestures like pinch/zoom that need multi-touch coordination
        if (((actionIndex == 1 && pointerCount == 2) || (actionIndex == 0 && pointerCount == 2)) && maxPointerCountInGesture == 2 && confirmedScaleTranslateGetter.get()) {
            Pair<Integer, Integer> otherTouchPos;
            if (actionIndex == 1) {
                otherTouchPos = otherTouchPosGetter.apply(0);
            } else {
                otherTouchPos = otherTouchPosGetter.apply(1);
            }

            int otherTouchX = otherTouchPos.first;
            int otherTouchY = otherTouchPos.second;
            int midpointX = (eventX + otherTouchX) / 2;
            int midpointY = (eventY + otherTouchY) / 2;
            double spacing = Math.sqrt(Math.pow(eventX - otherTouchX, 2) + Math.pow(eventY - otherTouchY, 2));

            int frameTranslateX = midpointX - doubleFingerInitialMidpointXGetter.get();
            int frameTranslateY = midpointY - doubleFingerInitialMidpointYGetter.get();
            double doubleFingerInitialSpacing = doubleFingerInitialSpacingGetter.get();
            double zoomFactor;
            if (Double.isNaN(doubleFingerInitialSpacing)) {
                zoomFactor = 1.0d;
            } else {
                zoomFactor = spacing / doubleFingerInitialSpacing;
                if (!Double.isFinite(zoomFactor)) {
                    zoomFactor = 1.0d;
                }
                if (zoomFactor < 0.02d) {
                    zoomFactor = 0.02d;
                }
                if (zoomFactor > 30.0d) {
                    zoomFactor = 30.0d;
                }
            }

            scaleTransformCallback.report(frameTranslateX, frameTranslateY, zoomFactor, true);
            cancelTouch();
            return;
        }

        if ((actionIndex == 0 && pointerCount == 1) && maxPointerCountInGesture == 1 && confirmedScaleTranslateGetter.get()) {
            int frameTranslateX = eventX - lastTouchDownX;
            int frameTranslateY = eventY - lastTouchDownY;
            scaleTransformCallback.report(frameTranslateX, frameTranslateY, 1.0, true);
            cancelTouch();
            return;
        }

        lastTouchLocationX = lastTouchUpX = eventX;
        lastTouchLocationXRel = xRel;
        lastTouchLocationY = lastTouchUpY = eventY;
        lastTouchLocationYRel = yRel;
        lastTouchUpTime = eventTime;
    }

    private void checkForConfirmedDoubleFingerScaleTranslate(int eventX, int eventY) {
        if (actionIndex != 1 || maxPointerCountInGesture != 2) {
            return;
        }

        if (confirmedScaleTranslateGetter.get()) {
            return;
        }
        if (isPinchZoomTimedOut) {
            return;
        }

        Pair<Integer, Integer> touch0Pos = otherTouchPosGetter.apply(0);
        int touch0X = touch0Pos.first;
        int touch0Y = touch0Pos.second;

        double spacingNow = Math.sqrt(Math.pow(eventX - touch0X, 2) + Math.pow(eventY - touch0Y, 2));
        double doubleFingerInitialSpacing = doubleFingerInitialSpacingGetter.get();
        if (!Double.isNaN(doubleFingerInitialSpacing) && Math.abs(spacingNow - doubleFingerInitialSpacing) > 92.0d) {
            confirmedScaleTranslateSetter.accept(true);
        }
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, int xRel, int yRel, long eventTime)
    {
        if (cancelled) {
            return true;
        }

        if (actionIndex == 0) {
            if (true || modeLongPressNeededToDrag) {
                if (!hasEverScrolled && !confirmedMouseLeftButtonDown && distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD) && maxPointerCountInGesture == 1 && !confirmedScaleTranslateGetter.get()) {
                    confirmedScaleTranslateSetter.accept(true);
                }
            }

            if (!hasEverScrolled && confirmedScaleTranslateGetter.get() && maxPointerCountInGesture == 1) {
                scaleTransformCallback.report(eventX - lastTouchDownX, eventY - lastTouchDownY, 1.0, false);
            }
        }
        else if (actionIndex == 1) {
            checkForConfirmedDoubleFingerScaleTranslate(eventX, eventY);
            if (confirmedScaleTranslateGetter.get()) {
                if (pointerCount == 2 && maxPointerCountInGesture == 2) {
                    Pair<Integer, Integer> touch0Pos = otherTouchPosGetter.apply(0);

                    int touch0X = touch0Pos.first;
                    int touch0Y = touch0Pos.second;
                    int midpointX = (eventX + touch0X) / 2;
                    int midpointY = (eventY + touch0Y) / 2;
                    double spacing = Math.sqrt(Math.pow(eventX - touch0X, 2) + Math.pow(eventY - touch0Y, 2));

//                    Log.i(AbsoluteTouchContext.class.getName(), "touch0X touch0Y eventX eventY " + touch0X + ", " + touch0Y + ", " + eventX  + ", " + eventY);

                    int frameTranslateX = midpointX - doubleFingerInitialMidpointXGetter.get();
                    int frameTranslateY = midpointY - doubleFingerInitialMidpointYGetter.get();
                    double doubleFingerInitialSpacing = doubleFingerInitialSpacingGetter.get();
                    double zoomFactor;
                    if (Double.isNaN(doubleFingerInitialSpacing)) {
                        zoomFactor = 1.0d;
                    } else {
                        zoomFactor = spacing / doubleFingerInitialSpacing;
                        if (!Double.isFinite(zoomFactor)) {
                            zoomFactor = 1.0d;
                        }
                        if (zoomFactor < 0.02d) {
                            zoomFactor = 0.02d;
                        }
                        if (zoomFactor > 30.0d) {
                            zoomFactor = 30.0d;
                        }
                    }
                    scaleTransformCallback.report(frameTranslateX, frameTranslateY, zoomFactor, false);
                }
            }
        }

        lastTouchLocationX = eventX;
        lastTouchLocationXRel = xRel;
        lastTouchLocationY = eventY;
        lastTouchLocationYRel = yRel;

        return true;
    }

    @Override
    public void cancelTouch() {
//        confirmedScaleTranslateSetter.accept(false);
        doubleFingerInitialSpacingSetter.accept(Double.NaN);
        scaleTransformCallback.report(-1, -1, Double.NaN, true);
        cancelled = true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {
        this.pointerCount = pointerCount;

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount;
        }
    }
}
