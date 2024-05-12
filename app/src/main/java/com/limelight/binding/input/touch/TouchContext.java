package com.limelight.binding.input.touch;

public interface TouchContext {
    public static interface ScaleTransformCallback {
        void report(int transformX, int transformY, double scaleFactor, boolean confirm);
    }
    int getActionIndex();
    int getLastTouchX();
    int getLastTouchY();
    void setPointerCount(int pointerCount);
    boolean touchDownEvent(int eventX, int eventY, int xRel, int yRel, long eventTime, boolean isNewFinger);
    boolean touchMoveEvent(int eventX, int eventY, int xRel, int yRel, long eventTime);
    void touchUpEvent(int eventX, int eventY, int xRel, int yRel, long eventTime);
    void cancelTouch();
    boolean isCancelled();
}
