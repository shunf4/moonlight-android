package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

public class TrackpadContext implements TouchContext {
    private int lastTouchX = 0;
    private int lastTouchY = 0;
    private int originalTouchX = 0;
    private int originalTouchY = 0;
    private long originalTouchTime = 0;
    private boolean cancelled;
    private boolean confirmedMove;
    private boolean confirmedDrag;
    private boolean confirmedScroll;
    private double distanceMoved;
    private int pointerCount;
    private int maxPointerCountInGesture;
    private boolean isClickPending;
    private boolean isDblClickPending;

    private final NvConnection conn;
    private final int actionIndex;
    private final Handler handler;

    private boolean swapAxis = false;
    private float sensitivityX = 1;
    private float sensitivityY = 1;

    private static final int TAP_MOVEMENT_THRESHOLD = 20;
    private static final int TAP_DISTANCE_THRESHOLD = 25;
    private static final int TAP_TIME_THRESHOLD = 230;
    private static final int CLICK_RELEASE_DELAY = TAP_TIME_THRESHOLD;
    private static final int SCROLL_SPEED_FACTOR_X = 2;
    private static final int SCROLL_SPEED_FACTOR_Y = 3;

    public TrackpadContext(NvConnection conn, int actionIndex) {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public TrackpadContext(NvConnection conn, int actionIndex, boolean swapAxis, int sensitivityX, int sensitivityY) {
        this(conn, actionIndex);
        this.swapAxis = swapAxis;
        this.sensitivityX = (float) sensitivityX / 100;
        this.sensitivityY = (float) sensitivityY / 100;
    }

    @Override
    public int getActionIndex() {
        return actionIndex;
    }

    @Override
    public int getLastTouchX() {
        // unused
        return 0;
    }

    @Override
    public int getLastTouchY() {
        // unused
        return 0;
    }

    private boolean isWithinTapBounds(int touchX, int touchY) {
        int xDelta = Math.abs(touchX - originalTouchX);
        int yDelta = Math.abs(touchY - originalTouchY);
        return xDelta <= TAP_MOVEMENT_THRESHOLD && yDelta <= TAP_MOVEMENT_THRESHOLD;
    }

    private boolean isTap(long eventTime) {
        if (confirmedDrag || confirmedMove || confirmedScroll) {
            return false;
        }

        if (actionIndex + 1 != maxPointerCountInGesture) {
            return false;
        }

        long timeDelta = eventTime - originalTouchTime;
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD;
    }

    private byte getMouseButtonIndex() {
        if (pointerCount == 2) {
            return MouseButtonPacket.BUTTON_RIGHT;
        } else {
            return MouseButtonPacket.BUTTON_LEFT;
        }
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, int xRel, int yRel, long eventTime, boolean isNewFinger) {
        originalTouchX = lastTouchX = xRel;
        originalTouchY = lastTouchY = yRel;

        if (isNewFinger) {
            maxPointerCountInGesture = pointerCount;
            originalTouchTime = eventTime;
            cancelled = confirmedMove = confirmedScroll = false;
            distanceMoved = 0;
            if (isClickPending) {
                isClickPending = false;
                isDblClickPending = true;
                confirmedDrag = true;
            }
        } else {
            // Second finger released, should trigger right click immediately
            if (pointerCount == 1 && !confirmedMove) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                isClickPending = false;
                isDblClickPending = false;
                confirmedDrag = false;
            }
        }

        return true;
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, int xRel, int yRel, long eventTime) {
        if (cancelled) {
            return;
        }

        byte buttonIndex = getMouseButtonIndex();

        if (isDblClickPending) {
            handler.removeCallbacksAndMessages(null);
            conn.sendMouseButtonUp(buttonIndex);
            conn.sendMouseButtonDown(buttonIndex);
            conn.sendMouseButtonUp(buttonIndex);
            isClickPending = false;
            confirmedDrag = false;
        }
        else if (confirmedDrag) {
            handler.removeCallbacksAndMessages(null);
            conn.sendMouseButtonUp(buttonIndex);
            confirmedDrag = false;
        }
        else if (isTap(eventTime)) {
            conn.sendMouseButtonDown(buttonIndex);
            isClickPending = true;

            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(() -> {
                if (isClickPending) {
                    conn.sendMouseButtonUp(buttonIndex);
                    isClickPending = false;
                }
                isDblClickPending = false;
            }, CLICK_RELEASE_DELAY);
        }
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, int xRel, int yRel, long eventTime) {
        if (cancelled) {
            return true;
        }

        if (xRel != lastTouchX || yRel != lastTouchY) {
            checkForConfirmedMove(xRel, yRel);

            if (isDblClickPending) {
                isDblClickPending = false;
                confirmedDrag = true;
            }

            int absDeltaX = Math.abs(xRel - lastTouchX);
            int absDeltaY = Math.abs(yRel - lastTouchY);

            float deltaX, deltaY;
            if (swapAxis) {
                deltaY = (xRel < lastTouchX) ? -absDeltaX : absDeltaX;
                deltaX = (yRel < lastTouchY) ? -absDeltaY : absDeltaY;
            } else {
                deltaX = (xRel < lastTouchX) ? -absDeltaX : absDeltaX;
                deltaY = (yRel < lastTouchY) ? -absDeltaY : absDeltaY;
            }

            deltaX *= sensitivityX;
            deltaY *= sensitivityY;

            lastTouchX = xRel;
            lastTouchY = yRel;

            if (pointerCount == 1) {
                conn.sendMouseMove((short) deltaX, (short) deltaY);
            } else {
                if (actionIndex == 1) {
                    if (confirmedDrag) {
                        conn.sendMouseMove((short) deltaX, (short) deltaY);
                    } else if (pointerCount == 2) {
                        checkForConfirmedScroll();
                        if (confirmedScroll) {
                            if (absDeltaX > absDeltaY) {
                                conn.sendMouseHighResHScroll((short)(-deltaX * SCROLL_SPEED_FACTOR_X));
                                if (absDeltaY * 1.05 > absDeltaX) {
                                    conn.sendMouseHighResScroll((short)(deltaY * SCROLL_SPEED_FACTOR_Y));
                                }
                            } else {
                                conn.sendMouseHighResScroll((short)(deltaY * SCROLL_SPEED_FACTOR_Y));
                                if (absDeltaX * 1.05 >= absDeltaY) {
                                    conn.sendMouseHighResHScroll((short)(-deltaX * SCROLL_SPEED_FACTOR_X));
                                }
                            }
                        }
                    }
                }
            }
        }

        return true;
    }

    @Override
    public void cancelTouch() {
        cancelled = true;

        if (confirmedDrag) {
            conn.sendMouseButtonUp(getMouseButtonIndex());
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {
        if (pointerCount < this.pointerCount && confirmedDrag) {
            conn.sendMouseButtonUp(getMouseButtonIndex());
            confirmedDrag = false;
            confirmedMove = false;
            confirmedScroll = false;
            isClickPending = false;
            isDblClickPending = false;
        }

        this.pointerCount = pointerCount;

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount;
        }
    }

    private void checkForConfirmedMove(int eventX, int eventY) {
        // If we've already confirmed something, get out now
        if (confirmedMove || confirmedDrag) {
            return;
        }

        // If it leaves the tap bounds before the drag time expires, it's a move.
        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true;
            return;
        }

        // Check if we've exceeded the maximum distance moved
        distanceMoved += Math.sqrt(Math.pow(eventX - lastTouchX, 2) + Math.pow(eventY - lastTouchY, 2));
        if (distanceMoved >= TAP_DISTANCE_THRESHOLD) {
            confirmedMove = true;
        }
    }

    private void checkForConfirmedScroll() {
        confirmedScroll = (actionIndex == 1 && pointerCount == 2 && confirmedMove);
    }
}
