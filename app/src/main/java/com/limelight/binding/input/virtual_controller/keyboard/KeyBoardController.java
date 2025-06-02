/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller.keyboard;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeyBoardController {

    public enum ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons,
        DisableEnableButtons
    }

    public boolean shown = false;

    private static final boolean _PRINT_DEBUG_INFORMATION = false;

    private final NvConnection conn;
    private final Context context;
    private final Handler handler;

    private FrameLayout frame_layout = null;

    ControllerMode currentMode = ControllerMode.Active;

    private Map<Integer, Runnable> keyEventRunnableMap = new HashMap<>();

    private Button buttonConfigure = null;
    private Button buttonClearAll = null;
    private Button buttonAddKeys = null;

    private Vibrator vibrator;
    private List<keyBoardVirtualControllerElement> elements = new ArrayList<>();

    public KeyBoardController(final NvConnection conn, FrameLayout layout, final Context context) {
        this.conn = conn;
        this.frame_layout = layout;
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());

        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        // Configure button
        buttonConfigure = new Button(context);
        buttonConfigure.setAlpha(0.5f);
        buttonConfigure.setFocusable(false);
        buttonConfigure.setBackgroundResource(R.drawable.ic_keyboard_setting);

        // Add long click listener for moving the configure button
        buttonConfigure.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Toast.makeText(context, "You can now move the Configure button", Toast.LENGTH_SHORT).show();
                buttonConfigure.setTag("movable");
                vibrator.vibrate(100); // Give haptic feedback
                return true;
            }
        });

        // Add touch listener for moving
        buttonConfigure.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;
            private float lastTouchX, lastTouchY;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if ("movable".equals(view.getTag())) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            dX = view.getX() - event.getRawX();
                            dY = view.getY() - event.getRawY();
                            lastTouchX = event.getRawX();
                            lastTouchY = event.getRawY();
                            isMoving = false;
                            break;

                        case MotionEvent.ACTION_MOVE:
                            float newX = event.getRawX() + dX;
                            float newY = event.getRawY() + dY;
                            
                            // Check if actually moving (to differentiate from simple touch)
                            if (Math.abs(event.getRawX() - lastTouchX) > 5 || 
                                Math.abs(event.getRawY() - lastTouchY) > 5) {
                                isMoving = true;
                            }
                            
                            if (isMoving) {
                                // Keep button within screen bounds
                                newX = Math.max(0, Math.min(newX, frame_layout.getWidth() - view.getWidth()));
                                newY = Math.max(0, Math.min(newY, frame_layout.getHeight() - view.getHeight()));
                                
                                view.setX(newX);
                                view.setY(newY);
                            }
                            break;

                        case MotionEvent.ACTION_UP:
                            view.setTag(null);
                            if (!isMoving) {
                                // If not moving, trigger the click event
                                view.performClick();
                            }
                            break;
                    }
                    return true;
                }
                return false;
            }
        });

        buttonConfigure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String message;

                if (currentMode == ControllerMode.Active) {
                    currentMode = ControllerMode.DisableEnableButtons;
                    showElements();
                    showControlButtons(true);
                    message = context.getString(R.string.configuration_mode_disable_enable_buttons);
                } else if (currentMode == ControllerMode.DisableEnableButtons) {
                    currentMode = ControllerMode.MoveButtons;
                    showEnabledElements();
                    showControlButtons(false);
                    message = context.getString(R.string.configuration_mode_move_buttons);
                } else if (currentMode == ControllerMode.MoveButtons) {
                    currentMode = ControllerMode.ResizeButtons;
                    message = context.getString(R.string.configuration_mode_resize_buttons);
                } else {
                    currentMode = ControllerMode.Active;
                    KeyBoardControllerConfigurationLoader.saveProfile(KeyBoardController.this, context);
                    message = context.getString(R.string.configuration_mode_exiting);
                }

                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();

                buttonConfigure.invalidate();

                for (keyBoardVirtualControllerElement element : elements) {
                    element.invalidate();
                }
            }
        });

        // Clear All button
        buttonClearAll = new Button(context);
        buttonClearAll.setText("Clear All");
        buttonClearAll.setAlpha(0.7f);
        buttonClearAll.setVisibility(View.GONE);
        buttonClearAll.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("Clear All Keys");
            builder.setMessage("Are you sure you want to remove all keys?");
            builder.setPositiveButton("Yes", (dialog, which) -> {
                // Instead of removing elements, mark them as hidden
                for (keyBoardVirtualControllerElement element : elements) {
                    element.hidden = true;
                    element.setVisibility(View.GONE);
                }
                // Save the new state
                KeyBoardControllerConfigurationLoader.saveProfile(KeyBoardController.this, context);
                vibrate(KeyEvent.ACTION_DOWN);
            });
            builder.setNegativeButton("No", null);
            builder.show();
        });

        // Add Keys button
        buttonAddKeys = new Button(context);
        buttonAddKeys.setText("Add Keys");
        buttonAddKeys.setAlpha(0.7f);
        buttonAddKeys.setVisibility(View.GONE);
        buttonAddKeys.setOnClickListener(v -> showKeySelectionDialog());

        refreshLayout();
    }

    Handler getHandler() {
        return handler;
    }

    public void hide(boolean temporary) {
        for (keyBoardVirtualControllerElement element : elements) {
            element.setVisibility(View.GONE);
        }

        buttonConfigure.setVisibility(View.GONE);
        if (!temporary) {
            shown = false;
        };
    }

    public void hide() {
        hide(false);
    }

    public void show() {
        showEnabledElements();
        buttonConfigure.setVisibility(View.VISIBLE);
        shown = true;
    }

    public void showElements() {
        for (keyBoardVirtualControllerElement element : elements) {
            // In configuration mode, show all non-hidden elements
            if (currentMode == ControllerMode.DisableEnableButtons) {
                element.setVisibility(element.hidden ? View.GONE : View.VISIBLE);
            } else {
                element.setVisibility((element.hidden || !element.enabled) ? View.GONE : View.VISIBLE);
            }
        }
    }

    public void showEnabledElements() {
        for (keyBoardVirtualControllerElement element : elements) {
            // In configuration mode, show all non-hidden elements
            if (currentMode == ControllerMode.DisableEnableButtons) {
                element.setVisibility(element.hidden ? View.GONE : View.VISIBLE);
            } else {
                element.setVisibility((!element.hidden && element.enabled) ? View.VISIBLE : View.GONE);
            }
        }
    }

    public void toggleVisibility() {
        if (buttonConfigure.getVisibility() == View.VISIBLE) {
            hide();
        } else {
            show();
        }
    }

    public void removeElements() {
        for (keyBoardVirtualControllerElement element : elements) {
            frame_layout.removeView(element);
        }
        elements.clear();

        frame_layout.removeView(buttonConfigure);
        frame_layout.removeView(buttonClearAll);
        frame_layout.removeView(buttonAddKeys);
    }

    public void setOpacity(int opacity) {
        for (keyBoardVirtualControllerElement element : elements) {
            element.setOpacity(opacity);
        }
    }


    public void addElement(keyBoardVirtualControllerElement element, int x, int y, int width, int height) {
        elements.add(element);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
        layoutParams.setMargins(x, y, 0, 0);

        frame_layout.addView(element, layoutParams);
    }

    public List<keyBoardVirtualControllerElement> getElements() {
        return elements;
    }

    private static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
            LimeLog.info("VirtualController: " + text);
        }
    }

    public void refreshLayout() {
        removeElements();

        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        int buttonSize = (int) (screen.heightPixels * 0.06f);

        // Configure button at original position
        FrameLayout.LayoutParams configParams = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        configParams.leftMargin = 20 + buttonSize;
        configParams.topMargin = 15;
        frame_layout.addView(buttonConfigure, configParams);

        // Measure the widths of both buttons
        buttonClearAll.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        buttonAddKeys.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int clearAllWidth = buttonClearAll.getMeasuredWidth();
        int addKeysWidth = buttonAddKeys.getMeasuredWidth();
        
        // Calculate center positions
        int totalWidth = clearAllWidth + addKeysWidth + 3; // 3 pixels spacing
        int screenCenter = screen.widthPixels / 2;
        int startX = screenCenter - (totalWidth / 2);

        // Clear All button
        FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        clearParams.leftMargin = startX;
        clearParams.topMargin = 15;
        frame_layout.addView(buttonClearAll, clearParams);

        // Add Keys button
        FrameLayout.LayoutParams addParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        addParams.leftMargin = startX + clearAllWidth + 3; // Position right after Clear All with 3px spacing
        addParams.topMargin = 15;
        frame_layout.addView(buttonAddKeys, addParams);

        // Apply default layout
        KeyBoardControllerConfigurationLoader.createDefaultLayout(this, context, conn);
        KeyBoardControllerConfigurationLoader.loadFromPreferences(this, context);
    }

    public ControllerMode getControllerMode() {
        return currentMode;
    }

    public void sendKeyEvent(KeyEvent keyEvent) {
        if (Game.instance == null || !Game.instance.connected) {
            return;
        }
        //1-鼠标 0-按键 2-摇杆 3-十字键
        if (keyEvent.getSource() == 1) {
            Game.instance.mouseButtonEvent(keyEvent.getKeyCode(), KeyEvent.ACTION_DOWN == keyEvent.getAction());
        } else {
            Game.instance.onKey(null, keyEvent.getKeyCode(), keyEvent);
        }

        if (keyEvent.getSource() != 2) {
            vibrate(keyEvent.getAction());
        }
    }

    public void sendMouseMove(int x,int y){
        if (Game.instance == null || !Game.instance.connected) {
            return;
        }
        Game.instance.mouseMove(x,y);
    }

    public void vibrate(int action) {
        if (PreferenceConfiguration.readPreferences(context).enableKeyboardVibrate && vibrator.hasVibrator()) {
            switch (action) {
                case KeyEvent.ACTION_DOWN:
                    frame_layout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    break;
                case KeyEvent.ACTION_UP:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        frame_layout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                    } else {
                        frame_layout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    break;
                default:
                    frame_layout.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
    }

    private void showControlButtons(boolean show) {
        int visibility = show ? View.VISIBLE : View.GONE;
        buttonClearAll.setVisibility(visibility);
        buttonAddKeys.setVisibility(visibility);
    }

    private void showKeySelectionDialog() {
        try {
            InputStream is = context.getAssets().open("config/keyboard.json");
            int length = is.available();
            byte[] buffer = new byte[length];
            is.read(buffer);
            String jsonConfig = new String(buffer, "utf8");
            
            JSONObject json = new JSONObject(jsonConfig);
            JSONObject data = json.getJSONObject("data");
            JSONArray keystrokeList = data.getJSONArray("keystroke");
            JSONArray mouseList = data.getJSONArray("mouse");
            JSONArray rockerList = data.getJSONArray("rocker");
            JSONArray dpadList = data.getJSONArray("dpad");

            // Add backspace key
            JSONObject backspaceKey = new JSONObject();
            backspaceKey.put("name", "⌫");
            backspaceKey.put("type", 0);  // keyboard type
            backspaceKey.put("code", KeyEvent.KEYCODE_DEL);
            keystrokeList.put(backspaceKey);

            // Calculate total number of items
            int totalItems = keystrokeList.length() + mouseList.length() + rockerList.length() + dpadList.length();
            String[] keyNames = new String[totalItems];
            boolean[] checkedItems = new boolean[totalItems];
            JSONArray allItems = new JSONArray();

            // Add keyboard keys
            for (int i = 0; i < keystrokeList.length(); i++) {
                JSONObject key = keystrokeList.getJSONObject(i);
                key.put("type", 0); // keyboard type
                allItems.put(key);
                keyNames[i] = key.getString("name");
            }

            // Add mouse buttons
            int currentIndex = keystrokeList.length();
            for (int i = 0; i < mouseList.length(); i++) {
                JSONObject obj = mouseList.getJSONObject(i);
                obj.put("type", 1); // mouse type
                allItems.put(obj);
                keyNames[currentIndex + i] = obj.getString("name");
            }

            // Add rocker (joystick) controls
            currentIndex += mouseList.length();
            for (int i = 0; i < rockerList.length(); i++) {
                JSONObject obj = rockerList.getJSONObject(i);
                obj.put("type", 2); // rocker type
                allItems.put(obj);
                keyNames[currentIndex + i] = obj.getString("name") + " (Joystick)";
            }

            // Add dpad controls
            currentIndex += rockerList.length();
            for (int i = 0; i < dpadList.length(); i++) {
                JSONObject obj = dpadList.getJSONObject(i);
                obj.put("type", 3); // dpad type
                allItems.put(obj);
                keyNames[currentIndex + i] = obj.getString("name") + " (D-Pad)";
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("Select Keys to Add");
            builder.setMultiChoiceItems(keyNames, checkedItems, (dialog, which, isChecked) -> {
                checkedItems[which] = isChecked;
            });

            builder.setPositiveButton("Add", (dialog, which) -> {
                DisplayMetrics screen = context.getResources().getDisplayMetrics();
                int height = screen.heightPixels;
                
                // Calculate button size using the same logic as createDefaultLayout
                int BUTTON_SIZE = 10;
                int w = KeyBoardControllerConfigurationLoader.screenScale(BUTTON_SIZE, height);
                int maxW = screen.widthPixels / 18;

                if (w > maxW) {
                    BUTTON_SIZE = KeyBoardControllerConfigurationLoader.screenScaleSwitch(maxW, height);
                    w = KeyBoardControllerConfigurationLoader.screenScale(BUTTON_SIZE, height);
                }

                // Get current element positions to avoid overlap
                List<Rect> existingPositions = new ArrayList<>();
                for (keyBoardVirtualControllerElement element : elements) {
                    if (element.getVisibility() == View.VISIBLE) {
                        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) element.getLayoutParams();
                        existingPositions.add(new Rect(
                            params.leftMargin,
                            params.topMargin,
                            params.leftMargin + params.width,
                            params.topMargin + params.height
                        ));
                    }
                }

                // Add selected keys
                for (int i = 0; i < checkedItems.length; i++) {
                    if (checkedItems[i]) {
                        try {
                            JSONObject obj = allItems.getJSONObject(i);
                            int type = obj.optInt("type", 0);
                            
                            // Find non-overlapping position
                            Point position = findNonOverlappingPosition(existingPositions, type >= 2 ? (int)(w * 2.5) : w);
                            
                            if (type == 2) { // Rocker (joystick)
                                String elementId = obj.getString("elementId");
                                int[] keys = new int[]{
                                    obj.getInt("upCode"),
                                    obj.getInt("downCode"),
                                    obj.getInt("leftCode"),
                                    obj.getInt("rightCode"),
                                    obj.getInt("middleCode")
                                };
                                
                                addElement(KeyBoardControllerConfigurationLoader.createKeyBoardAnalogStickButton(
                                    this, elementId, context, keys),
                                    position.x, position.y,
                                    (int)(w * 2.5), (int)(w * 2.5)
                                );
                                
                                existingPositions.add(new Rect(position.x, position.y, 
                                    position.x + (int)(w * 2.5), position.y + (int)(w * 2.5)));
                                
                            } else if (type == 3) { // D-pad
                                String elementId = obj.getString("elementId");
                                addElement(KeyBoardControllerConfigurationLoader.createDiaitalPadButton(
                                    elementId,
                                    obj.getInt("leftCode"),
                                    obj.getInt("rightCode"),
                                    obj.getInt("upCode"),
                                    obj.getInt("downCode"),
                                    this, context),
                                    position.x, position.y,
                                    (int)(w * 2.5), (int)(w * 2.5)
                                );
                                
                                existingPositions.add(new Rect(position.x, position.y, 
                                    position.x + (int)(w * 2.5), position.y + (int)(w * 2.5)));
                                
                            } else {
                                String name = obj.getString("name");
                                int code = obj.getInt("code");
                                int switchButton = obj.optInt("switchButton");
                                String elementId = type == 0 ? "key_" + code : "m_" + code;
                                if (switchButton == 1) {
                                    elementId = type == 0 ? "key_s_" + code : "m_s_" + code;
                                }

                                if (elementId.equals("m_9") || elementId.equals("m_10") || elementId.equals("m_11")) {
                                    addElement(KeyBoardControllerConfigurationLoader.createDigitalTouchButton(
                                        elementId, code, type, 1, name, -1, this, context),
                                        position.x, position.y, w, w);
                                } else {
                                    addElement(KeyBoardControllerConfigurationLoader.createDigitalButton(
                                        elementId, code, type, 1, name, -1, 
                                        PreferenceConfiguration.readPreferences(context).stickyModifierKey && 
                                        KeyBoardControllerConfigurationLoader.isModifierKey(code), 
                                        this, context),
                                        position.x, position.y, w, w);
                                }

                                existingPositions.add(new Rect(position.x, position.y, position.x + w, position.y + w));
                            }
                            
                            vibrate(KeyEvent.ACTION_DOWN);
                        } catch (JSONException e) {
                            e.printStackTrace();
                            Toast.makeText(context, "Error adding key: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });

            builder.setNegativeButton("Cancel", null);
            builder.show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "Error loading keyboard configuration: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Point findNonOverlappingPosition(List<Rect> existingPositions, int elementSize) {
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        int screenWidth = screen.widthPixels;
        int screenHeight = screen.heightPixels;
        int spacing = 10; // Minimum spacing between elements

        // Start from top of screen with some margin
        int startY = 100;
        int x = spacing;
        int y = startY;

        while (y + elementSize < screenHeight) {
            boolean overlaps = false;
            Rect newPosition = new Rect(x, y, x + elementSize, y + elementSize);

            for (Rect existing : existingPositions) {
                if (Rect.intersects(existing, newPosition)) {
                    overlaps = true;
                    break;
                }
            }

            if (!overlaps) {
                return new Point(x, y);
            }

            // Move right
            x += elementSize + spacing;

            // If reached screen width, move to next row
            if (x + elementSize > screenWidth) {
                x = spacing;
                y += elementSize + spacing;
            }
        }

        // If no space found, start a new row at the top
        return new Point(spacing, startY);
    }

    private int screenScale(int units, int height) {
        return (int) (((float) height / (float) 72) * (float) units);
    }
}
