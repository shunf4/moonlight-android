package com.limelight.binding.input.touch;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.MutableBoolean;
import android.util.Pair;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@TargetApi(Build.VERSION_CODES.N)
public class RelativeTouchContext implements TouchContext {
    private int lastTouchX = 0;
    private int lastTouchY = 0;
    private int originalTouchX = 0;
    private int originalTouchY = 0;
    private long originalTouchTime = 0;
    private boolean cancelled;
    private boolean confirmedMove;
    private boolean confirmedDrag;
    private boolean confirmedHold;
    private Supplier<Boolean> confirmedScaleTranslateGetter;
    private Consumer<Boolean> confirmedScaleTranslateSetter;
    private boolean confirmedScroll;
    private boolean confirmedDoubleClickDragTransform;
    private double distanceMoved;
    private Supplier<Double> doubleFingerInitialSpacingGetter;
    private Consumer<Double> doubleFingerInitialSpacingSetter;
    private Supplier<Integer> doubleFingerInitialMidpointXGetter;
    private Consumer<Integer> doubleFingerInitialMidpointXSetter;
    private Supplier<Integer> doubleFingerInitialMidpointYGetter;
    private Consumer<Integer> doubleFingerInitialMidpointYSetter;
    private boolean isPinchZoomTimedOut;
    private double xFactor, yFactor;
    private int pointerCount;
    private int maxPointerCountInGesture;

    private final NvConnection conn;
    private final int actionIndex;
    private final int referenceWidth;
    private final int referenceHeight;
    private final int outerScreenWidth;
    private final int outerScreenHeight;
    private final int edgeSingleFingerScrollWidth;
    private final View targetView;
    private final PreferenceConfiguration prefConfig;
    private final Handler handler;
    private final Supplier<Long> lastLeftMouseTapTimeGetter;
    private final Consumer<Long> lastLeftMouseTapTimeSetter;
    private final boolean shouldDoubleClickDragTranslate;
    private final boolean shouldRelativeLongPressRightClick;

    private final Vibrator vibrator;

    private final Function<Integer, Pair<Integer, Integer>> otherTouchPosGetter;
    private final ScaleTransformCallback scaleTransformCallback;
    private byte lastDragMouseIndex = (byte) 0xFF;

    private final Runnable dragTimerRunnable = new Runnable() {
        @Override
        public void run() {
            // shunf4: using holdTimerRunnable instead
            if (true) {
                return;
            }

            // Check if someone already set move
            if (confirmedMove) {
                return;
            }

            // The drag should only be processed for the primary finger
            if (actionIndex != maxPointerCountInGesture - 1) {
                return;
            }

            // We haven't been cancelled before the timer expired so begin dragging
            confirmedDrag = true;
//            Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "isDrag");
            byte mouseButtonIndex = getMouseButtonIndex();
            if (mouseButtonIndex != (byte)0xFF) {
                lastDragMouseIndex = mouseButtonIndex;
                conn.sendMouseButtonDown(mouseButtonIndex);
            }
        }
    };

    private final Runnable shortHoldRightClickVibrateTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, 100));
            } else {
                vibrator.vibrate(150);
            }
        }
    };

    private final Runnable holdTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (confirmedMove) {
                return;
            }

            if (!(
                    (actionIndex == 0 && maxPointerCountInGesture == 1 && pointerCount == 1)
                    // conflicts with scaleTranslate
//                    || (actionIndex == 1 && maxPointerCountInGesture == 2 && pointerCount == 2)
            )) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(400, 210));
            } else {
                vibrator.vibrate(600);
            }

            confirmedHold = true;
            byte mouseButtonIndex = getMouseButtonIndex();
            if (mouseButtonIndex != (byte)0xFF) {
                conn.sendMouseButtonDown(mouseButtonIndex);
            }
        }
    };

    private final Runnable scaleTranslateHoldTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (confirmedMove || confirmedScroll || confirmedScaleTranslateGetter.get() || confirmedDoubleClickDragTransform) {
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
            confirmedScroll = false;
//            Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "isScaleTranslate 1");
        }
    };

    private final Runnable scaleTranslatePinchZoomTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (confirmedScaleTranslateGetter.get() || isPinchZoomTimedOut || confirmedDoubleClickDragTransform) {
                return;
            }

            if (maxPointerCountInGesture != 2 && pointerCount != 2 && actionIndex != 1) {
                return;
            }

            isPinchZoomTimedOut = true;
//            Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "scaleTranslate timedout");
        }
    };

    // Indexed by MouseButtonPacket.BUTTON_XXX - 1
    private final Runnable[] buttonUpRunnables = new Runnable[] {
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2);
                }
            }
    };

    private static final int TAP_MOVEMENT_THRESHOLD = 20;
    private static final int TAP_DISTANCE_THRESHOLD = 25;
    private static final int TAP_TIME_THRESHOLD = 250;
    private static final int DRAG_TIME_THRESHOLD = 650;

    private static final int SCROLL_SPEED_FACTOR = 5;

    public RelativeTouchContext(NvConnection conn, int actionIndex,
                                int referenceWidth, int referenceHeight,
                                View view, PreferenceConfiguration prefConfig,
                                int outerScreenWidth, int outerScreenHeight,
                                int edgeSingleFingerScrollWidth,
                                boolean shouldDoubleClickDragTranslate,
                                boolean shouldRelativeLongPressRightClick,
                                Vibrator vibrator,
                                Supplier<Long> lastLeftMouseTapTimeGetter,
                                Consumer<Long> lastLeftMouseTapTimeSetter,
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
        this.referenceWidth = referenceWidth;
        this.referenceHeight = referenceHeight;
        this.targetView = view;
        this.prefConfig = prefConfig;
        this.handler = new Handler(Looper.getMainLooper());

        this.outerScreenWidth = outerScreenWidth;
        this.outerScreenHeight = outerScreenHeight;
        this.edgeSingleFingerScrollWidth = edgeSingleFingerScrollWidth;
        this.shouldDoubleClickDragTranslate = shouldDoubleClickDragTranslate;
        this.shouldRelativeLongPressRightClick = shouldRelativeLongPressRightClick;
        this.vibrator = vibrator;

        this.lastLeftMouseTapTimeGetter = lastLeftMouseTapTimeGetter;
        this.lastLeftMouseTapTimeSetter = lastLeftMouseTapTimeSetter;
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
        return lastTouchX;
    }

    @Override
    public int getLastTouchY() {
        return lastTouchY;
    }

    private boolean isWithinTapBounds(int touchX, int touchY)
    {
        int xDelta = Math.abs(touchX - originalTouchX);
        int yDelta = Math.abs(touchY - originalTouchY);
//        Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "isWithinTapBounds: " + (xDelta <= TAP_MOVEMENT_THRESHOLD &&
//                yDelta <= TAP_MOVEMENT_THRESHOLD));
        return xDelta <= TAP_MOVEMENT_THRESHOLD &&
                yDelta <= TAP_MOVEMENT_THRESHOLD;
    }

    private boolean isTap(long eventTime, byte mouseButtonIndex)
    {
        if (confirmedDrag || confirmedHold || confirmedMove || confirmedScroll || confirmedScaleTranslateGetter.get() || confirmedDoubleClickDragTransform) {
//            Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "isTap: false 1");
            return false;
        }

        if (mouseButtonIndex == (byte)0xFF) {
            return false;
        }

        // If this input wasn't the last finger down, do not report
        // a tap. This ensures we don't report duplicate taps for each
        // finger on a multi-finger tap gesture
        if (!((actionIndex + 1 == maxPointerCountInGesture) || (actionIndex == 0 && pointerCount == 1 && maxPointerCountInGesture > 1))) {
//            Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "isTap: false 2, maxPointerCountInGesture = " + maxPointerCountInGesture);
            return false;
        }

        long timeDelta = eventTime - originalTouchTime;
//        Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "isTap: (timeDelta <= TAP_TIME_THRESHOLD) = " + (timeDelta <= TAP_TIME_THRESHOLD));
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD;
    }

    private boolean isRightTap(long eventTime)
    {
        if (confirmedDrag || confirmedHold || confirmedMove || confirmedScroll || confirmedScaleTranslateGetter.get() || confirmedDoubleClickDragTransform) {
            return false;
        }

        if (!(actionIndex == 0 && pointerCount == 1 && maxPointerCountInGesture == 1)) {
            return false;
        }

        long timeDelta = eventTime - originalTouchTime;
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta >= 368 && timeDelta < 900;
    }

    private byte getMouseButtonIndex()
    {
        if ((actionIndex == 1) || (actionIndex == 0 && pointerCount == 1 && maxPointerCountInGesture == 2)) {
            return MouseButtonPacket.BUTTON_RIGHT;
        }
        else if (actionIndex == 0 && pointerCount == 1 && maxPointerCountInGesture == 1) {
            return MouseButtonPacket.BUTTON_LEFT;
        } else {
            return (byte)0xFF;
        }
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, int xRel, int yRel, long eventTime, boolean isNewFinger)
    {
        // Get the view dimensions to scale inputs on this touch
        xFactor = referenceWidth / (double)targetView.getWidth();
        yFactor = referenceHeight / (double)targetView.getHeight();

        originalTouchX = lastTouchX = eventX;
        originalTouchY = lastTouchY = eventY;

//        Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "touchDown");

        if (isNewFinger) {
//            Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "isNewFinger");
            maxPointerCountInGesture = pointerCount;
            originalTouchTime = eventTime;
            cancelled = confirmedDrag = confirmedHold = confirmedMove = confirmedScroll = confirmedDoubleClickDragTransform = false;
            confirmedScaleTranslateSetter.accept(false);
            distanceMoved = 0;
            doubleFingerInitialSpacingSetter.accept(100.0d);
            isPinchZoomTimedOut = false;

            if (actionIndex == 0) {
                // Start the timer for engaging a drag
                startDragTimer();
                startHoldTimer();
                if (shouldRelativeLongPressRightClick) {
                    startShortHoldRightClickVibrateTimer();
                }
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
        }

        return true;
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, int xRel, int yRel, long eventTime)
    {
        if (cancelled) {
            return;
        }

        // Cancel the drag timer
        cancelDragTimer();
        cancelHoldTimer();
        cancelScaleTranslateTimer();
        cancelShortHoldRightClickVibrateTimer();

//        Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "touchUp");

        byte buttonIndex = getMouseButtonIndex();

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
            double zoomFactor = spacing / doubleFingerInitialSpacingGetter.get();
            if (zoomFactor < 0.02d) {
                zoomFactor = 0.02d;
            }
            if (zoomFactor > 30.0d) {
                zoomFactor = 30.0d;
            }
            scaleTransformCallback.report(frameTranslateX, frameTranslateY, zoomFactor, true);
            cancelTouch();
            return;
        }

        if (confirmedDoubleClickDragTransform) {
            scaleTransformCallback.report(eventX - originalTouchX, eventY - originalTouchY, 1.0, true);
        }
        if (confirmedDrag) {
            // Raise the button after a drag
            conn.sendMouseButtonUp(lastDragMouseIndex);
        } else if (confirmedHold) {
            conn.sendMouseButtonUp(buttonIndex);
        }
        else if (shouldRelativeLongPressRightClick && isRightTap(eventTime)) {
            buttonIndex = MouseButtonPacket.BUTTON_RIGHT;
            conn.sendMouseButtonDown(buttonIndex);
            Runnable buttonUpRunnable = buttonUpRunnables[buttonIndex - 1];
            handler.removeCallbacks(buttonUpRunnable);
            handler.postDelayed(buttonUpRunnable, 100);
        }
        else if (isTap(eventTime, buttonIndex))
        {
//            Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "isTap, buttonIndex = " + buttonIndex);
            // Lower the mouse button
            conn.sendMouseButtonDown(buttonIndex);
            if (buttonIndex == MouseButtonPacket.BUTTON_LEFT) {
                lastLeftMouseTapTimeSetter.accept(eventTime);
            }

            // Release the mouse button in 100ms to allow for apps that use polling
            // to detect mouse button presses.
            Runnable buttonUpRunnable = buttonUpRunnables[buttonIndex - 1];
            handler.removeCallbacks(buttonUpRunnable);
            handler.postDelayed(buttonUpRunnable, 100);
        } else {
//            Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "isNotTap");
        }
    }

    private void startDragTimer() {
        cancelDragTimer();
        handler.postDelayed(dragTimerRunnable, DRAG_TIME_THRESHOLD);
    }

    private void startHoldTimer() {
        cancelHoldTimer();
        handler.postDelayed(holdTimerRunnable, shouldRelativeLongPressRightClick ? 900 : 650);
    }

    private void startScaleTranslateTimer() {
        cancelScaleTranslateTimer();
        handler.postDelayed(scaleTranslateHoldTimerRunnable, 700);
        handler.postDelayed(scaleTranslatePinchZoomTimerRunnable, 450);
    }

    private void startShortHoldRightClickVibrateTimer() {
        cancelShortHoldRightClickVibrateTimer();
        handler.postDelayed(shortHoldRightClickVibrateTimerRunnable, 368 + 20);
    }

    private void cancelDragTimer() {
        handler.removeCallbacks(dragTimerRunnable);
    }
    private void cancelHoldTimer() {
        handler.removeCallbacks(holdTimerRunnable);
    }

    private void cancelScaleTranslateTimer() {
        handler.removeCallbacks(scaleTranslateHoldTimerRunnable);
        handler.removeCallbacks(scaleTranslatePinchZoomTimerRunnable);
    }

    private void cancelShortHoldRightClickVibrateTimer() {
        handler.removeCallbacks(shortHoldRightClickVibrateTimerRunnable);
    }

    @SuppressLint("NewApi")
    private void checkForConfirmedDoubleClickDrag(int eventX, int eventY, long eventTime, MutableBoolean distanceAdded) {
        // If we've already confirmed something, get out now
        if (confirmedMove || confirmedHold || confirmedDrag || confirmedScaleTranslateGetter.get() || confirmedDoubleClickDragTransform) {
            return;
        }

        boolean yeah = false;
        if (!isWithinTapBounds(eventX, eventY)) {
            yeah = true;
        }

        if (!yeah) {
            // Check if we've exceeded the maximum distance moved
            addDistanceLazyOnceIfNeeded(eventX, eventY, distanceAdded);
            if (distanceMoved >= TAP_DISTANCE_THRESHOLD) {
                yeah = true;
            }
        }

//        Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "(originalTouchTime - lastLeftMouseTapTimeGetter.get()) " + (originalTouchTime - lastLeftMouseTapTimeGetter.get()) + " maxPointerCountInGesture " + maxPointerCountInGesture);

        if (yeah && (actionIndex == 0 && pointerCount == 1 && maxPointerCountInGesture == 1 && (originalTouchTime - lastLeftMouseTapTimeGetter.get()) < 122)) {
            if (shouldDoubleClickDragTranslate) {
                confirmedDoubleClickDragTransform = true;
                cancelDragTimer();
                cancelHoldTimer();
                cancelShortHoldRightClickVibrateTimer();
            } else {
                byte mouseButtonIndex = getMouseButtonIndex();
                if (mouseButtonIndex != (byte) 0xFF) {
                    cancelDragTimer();
                    cancelHoldTimer();
                    cancelShortHoldRightClickVibrateTimer();
                    // Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "isDrag");
                    confirmedDrag = true;
                    lastDragMouseIndex = mouseButtonIndex;
                    conn.sendMouseButtonDown(mouseButtonIndex);
                }
            }
        }
    }

    private void addDistanceLazyOnceIfNeeded(int eventX, int eventY, MutableBoolean distanceAdded) {
        // If we've already confirmed something, get out now
        if (confirmedMove || confirmedDrag || confirmedHold || confirmedScaleTranslateGetter.get() || confirmedDoubleClickDragTransform) {
            return;
        }
        if (!distanceAdded.value) {
            distanceAdded.value = true;
            distanceMoved += Math.sqrt(Math.pow(eventX - lastTouchX, 2) + Math.pow(eventY - lastTouchY, 2));
        }
    }

    private void checkForConfirmedMove(int eventX, int eventY, long eventTime, MutableBoolean distanceAdded) {
        // If we've already confirmed something, get out now
        if (confirmedMove || confirmedDrag || confirmedHold || confirmedScaleTranslateGetter.get() || confirmedDoubleClickDragTransform) {
            return;
        }

        // If it leaves the tap bounds before the drag time expires, it's a move.
        if (!isWithinTapBounds(eventX, eventY)) {
//            Log.i("relT", "confirmedMove 1");
            confirmedMove = true;
        } else {
            // Check if we've exceeded the maximum distance moved
            addDistanceLazyOnceIfNeeded(eventX, eventY, distanceAdded);
            if (distanceMoved >= TAP_DISTANCE_THRESHOLD) {
//            Log.i("relT", "confirmedMove 2");
                confirmedMove = true;
            }
        }

        if (confirmedMove) {
            cancelDragTimer();
            cancelHoldTimer();
            cancelShortHoldRightClickVibrateTimer();

            long timeDelta = eventTime - originalTouchTime;
//            Log.i("Rel", "timeDelta " + timeDelta);
            if (timeDelta >= 368) {
                if (!(
                        (actionIndex == 0 && maxPointerCountInGesture == 1 && pointerCount == 1)
                        // conflicts with scaleTranslate
//                    || (actionIndex == 1 && maxPointerCountInGesture == 2 && pointerCount == 2)
                )) {
                    return;
                }

                confirmedHold = true;
                byte mouseButtonIndex = getMouseButtonIndex();
                if (mouseButtonIndex != (byte)0xFF) {
                    conn.sendMouseButtonDown(mouseButtonIndex);
                }
            }
        }
    }

    private void checkForConfirmedDoubleFingerScroll() {
        // Enter scrolling mode if we've already left the tap zone
        // and we have 2 fingers on screen. Leave scroll mode if
        // we no longer have 2 fingers on screen
        boolean origConfirmedScroll = confirmedScroll;
        confirmedScroll = confirmedScroll || ((actionIndex == 1 && pointerCount == 2 && maxPointerCountInGesture == 2 && confirmedMove && !confirmedScaleTranslateGetter.get() && !confirmedDoubleClickDragTransform));

        if (confirmedScroll) {
            cancelHoldTimer();
            cancelDragTimer();
            cancelShortHoldRightClickVibrateTimer();
        }

        if (confirmedScroll && !origConfirmedScroll) {
//            Log.i("relT", "confirmedScroll");
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

    private void checkForConfirmedOneFingerScroll() {
        boolean origConfirmedScroll = confirmedScroll;
        confirmedScroll = confirmedScroll || ((actionIndex == 0 && pointerCount == 1 && maxPointerCountInGesture == 1 && decideIsSingleFingerScrollFromTouchX(originalTouchX) && confirmedMove && !confirmedScaleTranslateGetter.get()));
        if (confirmedScroll) {
            cancelHoldTimer();
            cancelDragTimer();
            cancelShortHoldRightClickVibrateTimer();
        }
        if (confirmedScroll && !origConfirmedScroll) {
//            Log.i("relT", "confirmedScroll");
        }
    }

    private void checkForConfirmedScaleTranslate(int eventX, int eventY) {
        if (actionIndex != 1 || maxPointerCountInGesture != 2 || !confirmedMove || !confirmedScroll) {
            return;
        }
        if (confirmedDrag || confirmedHold || confirmedScaleTranslateGetter.get()) {
            return;
        }
        if (isPinchZoomTimedOut) {
            return;
        }

        Pair<Integer, Integer> touch0Pos = otherTouchPosGetter.apply(0);
        int touch0X = touch0Pos.first;
        int touch0Y = touch0Pos.second;

        double spacingNow = Math.sqrt(Math.pow(eventX - touch0X, 2) + Math.pow(eventY - touch0Y, 2));
        if (Math.abs(spacingNow - doubleFingerInitialSpacingGetter.get()) > 92.0d) {
            confirmedScaleTranslateSetter.accept(true);
            confirmedScroll = false;
            cancelScaleTranslateTimer();
//            Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "isScaleTranslate 2");
        }
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, int xRel, int yRel, long eventTime)
    {
        if (cancelled) {
            return true;
        }

        MutableBoolean distanceAdded = new MutableBoolean(false);

//        Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "eventX = " + eventX + ", eventY = " + eventY + ", lastTouchX = " + lastTouchX + ", lastTouchY = " + lastTouchY + ", originalTouchX = " + originalTouchX + ", originalTouchY = " + originalTouchY);

        if (eventX != lastTouchX || eventY != lastTouchY)
        {
            checkForConfirmedDoubleClickDrag(eventX, eventY, eventTime, distanceAdded);
            checkForConfirmedMove(eventX, eventY, eventTime, distanceAdded);
            checkForConfirmedOneFingerScroll();
            checkForConfirmedDoubleFingerScroll();
            checkForConfirmedScaleTranslate(eventX, eventY);

            int deltaX = eventX - lastTouchX;
            int deltaY = eventY - lastTouchY;

            // Scale the deltas based on the factors passed to our constructor
            deltaX = (int) Math.round((double) Math.abs(deltaX) * xFactor);
            deltaY = (int) Math.round((double) Math.abs(deltaY) * yFactor);

            // Fix up the signs
            if (eventX < lastTouchX) {
                deltaX = -deltaX;
            }
            if (eventY < lastTouchY) {
                deltaY = -deltaY;
            }

            if (actionIndex == 0) {
                if (pointerCount == 2) {

                } else if (confirmedScroll) {
                    conn.sendMouseHighResScroll((short) -(deltaY * SCROLL_SPEED_FACTOR));
                } else if (confirmedDoubleClickDragTransform) {
                    scaleTransformCallback.report(eventX - originalTouchX, eventY - originalTouchY, 1.0, false);
                } else {
                    if (prefConfig.absoluteMouseMode) {
                        conn.sendMouseMoveAsMousePosition(
                                (short) deltaX,
                                (short) deltaY,
                                (short) targetView.getWidth(),
                                (short) targetView.getHeight());
                    }
                    else {
                        conn.sendMouseMove((short) deltaX, (short) deltaY);
                    }
                }
            }
            else {
                if (actionIndex == 1 && pointerCount == 2) {
                    if (confirmedScroll && !confirmedScaleTranslateGetter.get()) {
                        conn.sendMouseHighResScroll((short)(deltaY * SCROLL_SPEED_FACTOR));
                    }
                }

                if (actionIndex == 1 && pointerCount == 2 && maxPointerCountInGesture == 2 && confirmedScaleTranslateGetter.get()) {
                    Pair<Integer, Integer> touch0Pos = otherTouchPosGetter.apply(0);
                    int touch0X = touch0Pos.first;
                    int touch0Y = touch0Pos.second;
                    int midpointX = (eventX + touch0X) / 2;
                    int midpointY = (eventY + touch0Y) / 2;
                    double spacing = Math.sqrt(Math.pow(eventX - touch0X, 2) + Math.pow(eventY - touch0Y, 2));

                    int frameTranslateX = midpointX - doubleFingerInitialMidpointXGetter.get();
                    int frameTranslateY = midpointY - doubleFingerInitialMidpointYGetter.get();
                    double zoomFactor = spacing / doubleFingerInitialSpacingGetter.get();
                    if (!Double.isFinite(zoomFactor)) {
                        zoomFactor = 1.0d;
                    }
                    if (zoomFactor < 0.02d) {
                        zoomFactor = 0.02d;
                    }
                    if (zoomFactor > 30.0d) {
                        zoomFactor = 30.0d;
                    }
                    scaleTransformCallback.report(frameTranslateX, frameTranslateY, zoomFactor, false);
                }
            }

            // If the scaling factor ended up rounding deltas to zero, wait until they are
            // non-zero to update lastTouch that way devices that report small touch events often
            // will work correctly
            if (deltaX != 0) {
                lastTouchX = eventX;
            }
            if (deltaY != 0) {
                lastTouchY = eventY;
            }
        }

        return true;
    }

    @Override
    public void cancelTouch() {
//        Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "cancelTouch");
        cancelled = true;

        // Cancel the drag timer
        cancelDragTimer();
        cancelHoldTimer();
        cancelScaleTranslateTimer();
        cancelShortHoldRightClickVibrateTimer();

        // If it was a confirmed drag, we'll need to raise the button now
        if (confirmedDrag) {
            conn.sendMouseButtonUp(lastDragMouseIndex);
        }
        if (confirmedHold) {
            byte mi = getMouseButtonIndex();
            if (mi != (byte)0xFF) {
                conn.sendMouseButtonUp(mi);
            }
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {
//        Log.i("relT", "[" + actionIndex + "/" + pointerCount + "/" + System.identityHashCode(this)  + "] " + "setPointerCount " + this.pointerCount + " -> " + pointerCount);
        this.pointerCount = pointerCount;

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount;
        }

        if (actionIndex > pointerCount - 1) {
            cancelDragTimer();
            cancelHoldTimer();
            cancelScaleTranslateTimer();
            cancelShortHoldRightClickVibrateTimer();
        }
    }
}
