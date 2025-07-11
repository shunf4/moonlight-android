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
public class AbsoluteTouchContext implements TouchContext {
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
    
    private final byte buttonPrimary;
    private final byte buttonSecondary;

    private final Runnable longPressRightClickRunnable = new Runnable() {
        @Override
        public void run() {
            if (hasEverScrolled || confirmedScaleTranslateGetter.get()) {
                return;
            }
            // This timer should have already expired, but cancel it just in case
//            cancelTapDownTimer();

            // Switch from a left click to a right click after a long press

            if (confirmedMouseLeftButtonDown) {
                if (!leftButtonAlreadyUp) {
                    leftButtonAlreadyUp = true;
                    conn.sendMouseButtonUp(buttonPrimary);
                }
                confirmedMouseLeftButtonDown = false;
            } else {
                AbsoluteTouchContext.this.updatePosition(lastTouchLocationX ,lastTouchLocationY, lastTouchLocationXRel, lastTouchLocationYRel);
            }
//            conn.sendMouseButtonDown(buttonSecondary);
            confirmedLongPressRightClick = true;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, 100));
            } else {
                vibrator.vibrate(150);
            }
        }
    };

    private final Runnable longPressHoldRunnable = new Runnable() {
        @Override
        public void run() {
            if (hasEverScrolled || confirmedScaleTranslateGetter.get()) {
                return;
            }
            cancelLongPressRightClickTimer();

            if (confirmedLongPressRightClick) {
                confirmedLongPressRightClick = false;
//                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            }

            if (confirmedMouseLeftButtonDown) {
                // already holding
            } else {
                AbsoluteTouchContext.this.updatePosition(lastTouchLocationX, lastTouchLocationY, lastTouchLocationXRel, lastTouchLocationYRel);
                checkForMouseLeftButtonDown(true);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(400, 210));
            } else {
                vibrator.vibrate(600);
            }

            confirmedLongPressHold = true;
        }
    };

    private final Runnable scaleTranslateHoldTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (confirmedMouseLeftButtonDown || hasEverScrolled || confirmedScaleTranslateGetter.get()) {
                return;
            }

            if (maxPointerCountInGesture != 2 && pointerCount != 2 && actionIndex != 1) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(600, 120));
            } else {
                vibrator.vibrate(800);
            }

            confirmedScaleTranslateSetter.accept(true);
        }
    };

    private final Runnable scaleTranslatePinchZoomTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (confirmedScaleTranslateGetter.get() || isPinchZoomTimedOut) {
                return;
            }

            if (maxPointerCountInGesture != 2 && pointerCount != 2 && actionIndex != 1) {
                return;
            }

            isPinchZoomTimedOut = true;
//            Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "scaleTranslate timedout");
        }
    };

//    private final Runnable tapDownRunnable = new Runnable() {
//        @Override
//        public void run() {
//            // Start our tap
//            tapConfirmed();
//        }
//    };

    private final NvConnection conn;
    private final int actionIndex;
    private final View targetView;
    private final Handler handler;

    private final int outerScreenWidth;
    private final int outerScreenHeight;

    private final int edgeSingleFingerScrollWidth;
    private final float scrollFactor2;

    private boolean leftButtonAlreadyUp;

    private final Runnable leftButtonUpRunnable = new Runnable() {
        @Override
        public void run() {
            conn.sendMouseButtonUp(buttonPrimary);
        }
    };

    private static final int SCROLL_SPEED_FACTOR = 3;

    private static final int LONG_PRESS_DISTANCE_THRESHOLD = 30;

    private static final int DOUBLE_TAP_TIME_THRESHOLD = 250;
    private static final int DOUBLE_TAP_DISTANCE_THRESHOLD = 60;

    private static final int TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD = 100;
    private static final int TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD = 20;

    public AbsoluteTouchContext(NvConnection conn, int actionIndex, View view, boolean swapped,
                                int outerScreenWidth, int outerScreenHeight,
                                boolean modeLongPressNeededToDrag,
                                int edgeSingleFingerScrollWidth,
                                float scrollFactor2,
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

        if (swapped) {
            buttonPrimary = MouseButtonPacket.BUTTON_RIGHT;
            buttonSecondary = MouseButtonPacket.BUTTON_LEFT;
        }
        else {
            buttonPrimary = MouseButtonPacket.BUTTON_LEFT;
            buttonSecondary = MouseButtonPacket.BUTTON_RIGHT;
        }

        this.outerScreenWidth = outerScreenWidth;
        this.outerScreenHeight = outerScreenHeight;

        this.modeLongPressNeededToDrag = modeLongPressNeededToDrag;
        this.edgeSingleFingerScrollWidth = edgeSingleFingerScrollWidth;
        this.scrollFactor2 = scrollFactor2;
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

        if (actionIndex == 0) {
            // Start the timers
            // startTapDownTimer();
            startLongPressRightClickTimer();
            startLongPressHoldTimer();
        }

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
            startScaleTranslateTimer();
        }

        return true;
    }

    private boolean distanceExceeds(int deltaX, int deltaY, double limit) {
        return Math.sqrt(Math.pow(deltaX, 2) + Math.pow(deltaY, 2)) > limit;
    }

    private void updatePosition(int eventX, int eventY, int xRel, int yRel) {
        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        xRel = Math.min(Math.max(xRel, 0), targetView.getWidth());
        yRel = Math.min(Math.max(yRel, 0), targetView.getHeight());

        conn.sendMousePosition((short)xRel, (short)yRel, (short)targetView.getWidth(), (short)targetView.getHeight());
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, int xRel, int yRel, long eventTime)
    {
        if (cancelled) {
            return;
        }

        cancelScaleTranslateTimer();

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

        if (actionIndex == 0) {
            // Cancel the timers
            cancelLongPressRightClickTimer();
            cancelLongPressHoldTimer();
            cancelScaleTranslateTimer();
//            cancelTapDownTimer();

            lastMouseLeftClickX = -10000;
            lastMouseLeftClickY = -10000;

            // Raise the mouse buttons that we currently have down
            if (confirmedLongPressRightClick) {
                doRightTap();
                handler.postDelayed(() -> {
                    conn.sendMouseButtonUp(buttonSecondary);
                }, 100);
            } else if (confirmedMouseLeftButtonDown && !leftButtonAlreadyUp) {
                // including hold
                leftButtonAlreadyUp = true;
                conn.sendMouseButtonUp(buttonPrimary);
                lastMouseLeftClickX = eventX;
                lastMouseLeftClickY = eventY;
            } else {
                handler.removeCallbacks(leftButtonUpRunnable);
                if (!confirmedScaleTranslateGetter.get() && /* !hasEverScrolled won't work, because it's actionIndex 0 */ maxPointerCountInGesture == 1) {
                    // We'll need to send the touch down and up events now at the
                    // original touch down position.
                    checkForMouseLeftButtonDown(false);

                    // Release the left mouse button in 100ms to allow for apps that use polling
                    // to detect mouse button presses.
                    handler.postDelayed(leftButtonUpRunnable, 100);
                }
            }
        }

        lastTouchLocationX = lastTouchUpX = eventX;
        lastTouchLocationXRel = xRel;
        lastTouchLocationY = lastTouchUpY = eventY;
        lastTouchLocationYRel = yRel;
        lastTouchUpTime = eventTime;
    }

    private void startLongPressRightClickTimer() {
        cancelLongPressRightClickTimer();
        handler.postDelayed(longPressRightClickRunnable, 400);
    }

    private void startLongPressHoldTimer() {
        cancelLongPressHoldTimer();
        handler.postDelayed(longPressHoldRunnable, 1000);
    }

    private void startScaleTranslateTimer() {
        cancelScaleTranslateTimer();
        handler.postDelayed(scaleTranslateHoldTimerRunnable, 700);
        handler.postDelayed(scaleTranslatePinchZoomTimerRunnable, 450);
    }

    private void cancelScaleTranslateTimer() {
        handler.removeCallbacks(scaleTranslateHoldTimerRunnable);
        handler.removeCallbacks(scaleTranslatePinchZoomTimerRunnable);
    }

    private void cancelLongPressRightClickTimer() {
        handler.removeCallbacks(longPressRightClickRunnable);
    }

    private void cancelLongPressHoldTimer() {
        handler.removeCallbacks(longPressHoldRunnable);
    }

//    private void startTapDownTimer() {
//        cancelTapDownTimer();
//        handler.postDelayed(tapDownRunnable, TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD);
//    }

//    private void cancelTapDownTimer() {
//        handler.removeCallbacks(tapDownRunnable);
//    }

    private void checkForMouseLeftButtonDown(boolean isAboutToHold) {
        if (confirmedMouseLeftButtonDown || confirmedLongPressHold || hasEverScrolled || confirmedScaleTranslateGetter.get()) {
            return;
        }

        if (confirmedLongPressRightClick) {
            confirmedLongPressRightClick = false;
        }

        confirmedMouseLeftButtonDown = true;
//        cancelTapDownTimer();

        boolean shouldUpdatePosition = false;
        boolean distanceExceeds = distanceExceeds(lastTouchDownX - lastTouchUpX, lastTouchDownY - lastTouchUpY, 60);
        if (!isAboutToHold) {
            // Left button down at original position
            if (lastTouchDownTime - lastTouchUpTime > DOUBLE_TAP_TIME_THRESHOLD || distanceExceeds(lastTouchDownX - lastMouseLeftClickX, lastTouchDownY - lastMouseLeftClickY, DOUBLE_TAP_DISTANCE_THRESHOLD)) {
                // Don't reposition for finger down events within the deadzone. This makes double-clicking easier.
                shouldUpdatePosition = true;
            }
        } else {
            shouldUpdatePosition = true;
        }

        if (shouldUpdatePosition) {
            updatePosition(lastTouchDownX, lastTouchDownY, lastTouchDownXRel, lastTouchDownYRel);
        }

        if (absoluteTouchTapOnlyPlacesMouse && distanceExceeds && !isAboutToHold) {
            // do not click
        } else {
            conn.sendMouseButtonDown(buttonPrimary);
            leftButtonAlreadyUp = false;
        }
    }

    private void doRightTap() {
        if (confirmedMouseLeftButtonDown || hasEverScrolled || confirmedScaleTranslateGetter.get() || !confirmedLongPressRightClick || confirmedLongPressHold) {
            return;
        }

        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
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
            cancelScaleTranslateTimer();
        }
    }

    private boolean decideIsSingleFingerScrollFromTouchX(int originalTouchX) {
        if (outerScreenWidth <= 0) {
            return false;
        }
        if (edgeSingleFingerScrollWidth < 0) {
            return originalTouchX + edgeSingleFingerScrollWidth < 0;
        }
        if (edgeSingleFingerScrollWidth >= 10000) {
            int x = edgeSingleFingerScrollWidth % 10000;
            return originalTouchX - x < 0 || originalTouchX + x > outerScreenWidth;
        }
        return originalTouchX + edgeSingleFingerScrollWidth > outerScreenWidth;
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, int xRel, int yRel, long eventTime)
    {
        if (cancelled) {
            return true;
        }

        if (actionIndex == 0) {
            if (distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, LONG_PRESS_DISTANCE_THRESHOLD)) {
                // Moved too far since touch down. Cancel the long press timer.
                cancelLongPressRightClickTimer();
                cancelLongPressHoldTimer();
            }

            if (hasEverScrolled || ((!confirmedMouseLeftButtonDown
                    && distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD)
                    && outerScreenWidth != 0 && decideIsSingleFingerScrollFromTouchX(lastTouchDownX))
                    && maxPointerCountInGesture == 1 && !confirmedScaleTranslateGetter.get())) {
                hasEverScrolled = true;
                conn.sendMouseHighResScroll((short) -((eventY - lastTouchLocationY) * SCROLL_SPEED_FACTOR * scrollFactor2));
            }

            if (modeLongPressNeededToDrag) {
                if (!hasEverScrolled && !confirmedMouseLeftButtonDown && distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD) && maxPointerCountInGesture == 1 && !confirmedScaleTranslateGetter.get()) {
                    confirmedScaleTranslateSetter.accept(true);
                    cancelScaleTranslateTimer();
                }
            }

            if (shouldDoubleClickDragTranslate) {
                if (!confirmedScaleTranslateGetter.get() && !hasEverScrolled && !confirmedMouseLeftButtonDown && distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD) && maxPointerCountInGesture == 1 && !confirmedScaleTranslateGetter.get()) {
                    if (lastTouchDownTime - lastTouchUpTime > DOUBLE_TAP_TIME_THRESHOLD ||
                            distanceExceeds(lastTouchDownX - lastTouchUpX, lastTouchDownY - lastTouchUpY, DOUBLE_TAP_DISTANCE_THRESHOLD)) {
                    } else {
                        confirmedScaleTranslateSetter.accept(true);
                        cancelScaleTranslateTimer();
                    }
                }
            }

            if (!hasEverScrolled && confirmedScaleTranslateGetter.get() && maxPointerCountInGesture == 1) {
                scaleTransformCallback.report(eventX - lastTouchDownX, eventY - lastTouchDownY, 1.0, false);
            }

            // Ignore motion within the deadzone period after touch down
            if (!hasEverScrolled && (confirmedMouseLeftButtonDown || distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD)) && maxPointerCountInGesture == 1 && !confirmedScaleTranslateGetter.get()) {
                checkForMouseLeftButtonDown(false);
                updatePosition(eventX, eventY, xRel, yRel);
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
            } else {
                if (hasEverScrolled || distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD)) {
                    hasEverScrolled = true;
                    conn.sendMouseHighResScroll((short) ((eventY - lastTouchLocationY) * SCROLL_SPEED_FACTOR * scrollFactor2));
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
        cancelled = true;

        // Cancel the timers
        cancelLongPressRightClickTimer();
        cancelLongPressHoldTimer();
//        cancelTapDownTimer();
        cancelScaleTranslateTimer();

        // Raise the mouse buttons
        if (confirmedLongPressRightClick) {
            conn.sendMouseButtonUp(buttonSecondary);
        }
        else if (confirmedLongPressHold) {
            conn.sendMouseButtonUp(buttonPrimary);
            leftButtonAlreadyUp = true;
        }
        else if (confirmedMouseLeftButtonDown) {
            if (!leftButtonAlreadyUp) {
                leftButtonAlreadyUp = true;
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
            }
        }

//        confirmedScaleTranslateSetter.accept(false);
        doubleFingerInitialSpacingSetter.accept(Double.NaN);
        scaleTransformCallback.report(-1, -1, Double.NaN, true);
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

        if (pointerCount >= 2) {
            cancelLongPressRightClickTimer();
            cancelLongPressHoldTimer();
        }
    }
}
