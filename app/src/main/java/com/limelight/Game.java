package com.limelight;


import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.capture.InputCaptureManager;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.input.capture.NullCaptureProvider;
import com.limelight.binding.input.touch.AbsoluteTouchContext;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.binding.input.evdev.EvdevListener;
import com.limelight.binding.input.touch.ScaleTranslateOnlyTouchContext;
import com.limelight.binding.input.touch.TouchContext;
import com.limelight.binding.input.touch.TrackpadContext;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardController;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;
import com.limelight.ui.StreamView;
import com.limelight.utils.Dialog;
import com.limelight.utils.PanZoomHandler;
import com.limelight.utils.PerformanceDataTracker;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.util.Rational;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnGenericMotionListener;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.FrameLayout;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageButton;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


public class Game extends Activity implements SurfaceHolder.Callback,
        OnGenericMotionListener, OnTouchListener, NvConnectionListener, EvdevListener,
        OnSystemUiVisibilityChangeListener, GameGestures, StreamView.InputCallbacks,
        PerfOverlayListener, UsbDriverService.UsbDriverStateListener, View.OnKeyListener {
    public static Game instance;

    private int lastButtonState = 0;

    // Only 2 touches are supported
    private final TouchContext[] touchContextMap = new TouchContext[2];
    private final TouchContext[] trackpadContextMap = new TouchContext[2];
    private PanZoomHandler panZoomHandler;
    private long threeFingerDownTime = 0;
    private long fourFingerDownTime = 0;
    private long fiveFingerDownTime = 0;
    private float threeFingerDownAvgX = Float.NaN;
    private float threeFingerDownAvgY = Float.NaN;
    private float threeFingerUpAvgX = Float.NaN;
    private float threeFingerUpAvgY = Float.NaN;

    private static final int REFERENCE_HORIZ_RES = 1280;
    private static final int REFERENCE_VERT_RES = 720;

    private static final int STYLUS_DOWN_DEAD_ZONE_DELAY = 100;
    private static final int STYLUS_DOWN_DEAD_ZONE_RADIUS = 20;

    private static final int STYLUS_UP_DEAD_ZONE_DELAY = 150;
    private static final int STYLUS_UP_DEAD_ZONE_RADIUS = 50;

    private static final int THREE_FINGER_TAP_THRESHOLD = 300;
    private static final int FOUR_FINGER_TAP_THRESHOLD = 300;

    private static final int FOUR_FINGER_LONG_PRESS_THRESHOLD = 700;
    private static final int FIVE_FINGER_TAP_THRESHOLD = 300;

    private ControllerHandler controllerHandler;
    private KeyboardTranslator keyboardTranslator;
    private VirtualController virtualController;

    private KeyBoardController keyBoardController;

    private KeyBoardLayoutController keyBoardLayoutController;

    private PreferenceConfiguration prefConfig;
    private SharedPreferences tombstonePrefs;

    private int displayWidth;
    private int displayHeight;
    private int currentOrientation;

    private NvConnection conn;
    private SpinnerDialog spinner;
    private boolean displayedFailureDialog = false;
    private boolean connecting = false;
    public boolean connected = false;
    private boolean autoEnterPip = false;
    private boolean surfaceCreated = false;
    private boolean attemptedConnection = false;
    private int suppressPipRefCount = 0;
    private String pcName;
    private String appName;
    private NvApp app;
    private float desiredRefreshRate;

    private InputCaptureProvider inputCaptureProvider;
    private int modifierFlags = 0;
    private boolean grabbedInput = true;
    private boolean cursorVisible = false;
    private boolean isPanZoomMode = false;
    private boolean synthClickPending = false;
    private boolean pointerSwiping = false;
    private boolean waitingForAllModifiersUp = false;
    private int specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private StreamView streamView;
    private long synthTouchDownTime = 0;

    private boolean pendingDrag = false;
    private boolean isDragging = false;
    private float lastTouchDownX, lastTouchDownY;

    private View backgroundTouchView;

    private long lastAbsTouchUpTime = 0;
    private long lastAbsTouchDownTime = 0;
    private long lastThreeLeftRightSwipe = 0;
    private long lastThreeDownSwipe = 0;
    private float lastAbsTouchUpX, lastAbsTouchUpY;
    private float lastAbsTouchDownX, lastAbsTouchDownY;

    private boolean quitOnStop = false;
    private boolean isHidingOverlays;
    private boolean floatingButtonShown;
    private TextView notificationOverlayView;
    private int requestedNotificationOverlayVisibility = View.GONE;
    private View performanceOverlayView;

    private TextView performanceOverlayLite;

    private TextView performanceOverlayBig;

    private TextView channelDisabledHintView;

    private MediaCodecDecoderRenderer decoderRenderer;
    private boolean reportedCrash;

    private WifiManager.WifiLock highPerfWifiLock;
    private WifiManager.WifiLock lowLatencyWifiLock;

    private long lastLeftMouseTapTime = -1;

    private float savedTranslateX = 0.0f;
    private float savedTranslateY = 0.0f;
    private float savedScale = 1.0f;

    private float pendingTranslateX = Float.NaN;
    private float pendingTranslateY = Float.NaN;
    private float pendingScale = Float.NaN;

    private boolean confirmedScaleTranslate;
    private double doubleFingerInitialSpacing;
    private int doubleFingerInitialMidpointX;
    private int doubleFingerInitialMidpointY;

    private boolean connectedToUsbDriverService = false;
    private ServiceConnection usbDriverServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            UsbDriverService.UsbDriverBinder binder = (UsbDriverService.UsbDriverBinder) iBinder;
            binder.setListener(controllerHandler);
            binder.setStateListener(Game.this);
            binder.start();
            connectedToUsbDriverService = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            connectedToUsbDriverService = false;
        }
    };

    public static final String EXTRA_HOST = "Host";
    public static final String EXTRA_PORT = "Port";
    public static final String EXTRA_HTTPS_PORT = "HttpsPort";
    public static final String EXTRA_APP_NAME = "AppName";
    public static final String EXTRA_APP_UUID = "AppUUID";
    public static final String EXTRA_APP_ID = "AppId";
    public static final String EXTRA_UNIQUEID = "UniqueId";
    public static final String EXTRA_PC_UUID = "UUID";
    public static final String EXTRA_PC_NAME = "PcName";
    public static final String EXTRA_APP_HDR = "HDR";
    public static final String EXTRA_SERVER_CERT = "ServerCert";
    public static final String EXTRA_VDISPLAY = "VirtualDisplay";
    public static final String EXTRA_SERVER_COMMANDS = "ServerCommands";

    public static final String CLIPBOARD_IDENTIFIER = "ArtemisStreaming";

    private String appUUID;
    private String host;
    private int port;
    private int httpsPort;
    private int appId;
    private String uniqueId;
    private X509Certificate serverCert;
    private boolean vDisplay;
    private ArrayList<String> serverCommands;

    private ViewParent rootView;
    private ClipboardManager clipboardManager;
    private boolean clipboardSyncRunning = false;

    private NvHTTP httpConn;
    private Integer lastMouseMode = null;

    public interface GameMenuCallbacks {
        void showMenu(GameInputDevice devic);
        void hideMenu();
        boolean isMenuOpen();
    }

    public GameMenuCallbacks gameMenuCallbacks;

    private ImageButton floatingMenuButton;
    private float floatingButtonDX, floatingButtonDY;
    private boolean isButtonMoving = false;
    private static final float CLICK_ACTION_THRESHOLD = 5;
    private float floatingButtonStartX, floatingButtonStartY;

    @SuppressLint({"MissingInflatedId", "ClickableViewAccessibility"})
    public static boolean IS_ONYX_BOOX_DEVICE = false;

    static {
        try {
            Class<?> c = null;
            try {
                c = Class.forName("android.os.SystemProperties");
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            Method get = null;
            try {
                get = c.getMethod("get", String.class);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }

            String property1 = null;
            try {
                property1 = (String) get.invoke(c, "sys.onyx.idledelay");
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            String property2 = null;
            try {
                property2 = (String) get.invoke(c, "vendor.onyx_dump_enable");
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            }

            if ((property1 != null && !property1.isEmpty()) || (property2 != null && !property2.isEmpty())) {
                Log.w(Game.class.getName(), "set IS_ONYX_BOOX_DEVICE to true");
                IS_ONYX_BOOX_DEVICE = true;
            }
        } catch (Exception e) {
            Log.w(Game.class.getName(), Log.getStackTraceString(e));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        instance = this;

        UiHelper.setLocale(this);

        // We don't want a title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);

//        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        // Listen for UI visibility events
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(this);

        // Change volume button behavior
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Inflate the content
        setContentView(R.layout.activity_game);

        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        // Start the spinner
        spinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.conn_establishing_title),
                getResources().getString(R.string.conn_establishing_msg), true);

        // Read the stream preferences
        prefConfig = PreferenceConfiguration.readPreferences(this);

        // Full-screen
        if (!prefConfig.isCurrDeviceLikeOnyx) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        // If we're going to use immersive mode, we want to have
        // the entire screen
        if (prefConfig.isCurrDeviceLikeOnyx) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
//                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        
        tombstonePrefs = Game.this.getSharedPreferences("DecoderTombstone", 0);

        if (prefConfig.autoOrientation) {
            currentOrientation = getResources().getConfiguration().orientation;
        } else {
            currentOrientation = Configuration.ORIENTATION_LANDSCAPE;
        }

        boolean portraitMode = currentOrientation == Configuration.ORIENTATION_PORTRAIT;
        boolean shouldInvertDecoderResolution = portraitMode && prefConfig.autoInvertVideoResolution;

        displayWidth = shouldInvertDecoderResolution ? prefConfig.height : prefConfig.width;
        displayHeight = shouldInvertDecoderResolution ? prefConfig.width : prefConfig.height;

        // Enter landscape unless we're on a square screen
        setPreferredOrientationForCurrentDisplay();

        if (
                true || prefConfig.videoScaleMode == PreferenceConfiguration.ScaleMode.STRETCH ||
                        shouldIgnoreInsetsForResolution(displayWidth, displayHeight)
        ) {
            // Allow the activity to layout under notches if the fill-screen option
            // was turned on by the user or it's a full-screen native resolution
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }
        // Listen for non-touch events on the game surface
        streamView = findViewById(R.id.surfaceView);
        streamView.setOnGenericMotionListener(this);
        streamView.setOnKeyListener(this);
        streamView.setInputCallbacks(this);

        // Paint p = new Paint();
        // ColorMatrix cm = new ColorMatrix(new float[] {
        //         0f, 32f, 0f, 0f, 0f,
        //         32f, 0f, 0f, 0f, 0f,
        //         0f, 0f, 64f, 0f, 0f,
        //         0f, 0f, 0f, 1f, 0f
        // });
        // p.setColorFilter(new ColorMatrixColorFilter(cm));
//        streamView.setLayerType(View.LAYER_TYPE_SOFTWARE, p);

        //光标是否显示
        cursorVisible = prefConfig.enableMouseLocalCursor;

        //串流画面 顶部居中显示
        if(prefConfig.alignDisplayTopCenter){
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) streamView.getLayoutParams();
            params.gravity = Gravity.CENTER_HORIZONTAL|Gravity.TOP;
        }
        // Listen for touch events on the background touch view to enable trackpad mode
        // to work on areas outside of the StreamView itself. We use a separate View
        // for this rather than just handling it at the Activity level, because that
        // allows proper touch splitting, which the OSC relies upon.
        backgroundTouchView = findViewById(R.id.backgroundTouchView);
        backgroundTouchView.setOnTouchListener(this);
        backgroundTouchView.getRootView().getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                applyMouseMode(null);
            }
        });

        rootView = streamView.getParent();

        panZoomHandler = new PanZoomHandler(
                getApplicationContext(),
                this,
                streamView,
                prefConfig
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Request unbuffered input event dispatching for all input classes we handle here.
            // Without this, input events are buffered to be delivered in lock-step with VBlank,
            // artificially increasing input latency while streaming.
            streamView.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON | // Keyboards
                            InputDevice.SOURCE_CLASS_JOYSTICK | // Gamepads
                            InputDevice.SOURCE_CLASS_POINTER | // Touchscreens and mice (w/o pointer capture)
                            InputDevice.SOURCE_CLASS_POSITION | // Touchpads
                            InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            );
            backgroundTouchView.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON | // Keyboards
                            InputDevice.SOURCE_CLASS_JOYSTICK | // Gamepads
                            InputDevice.SOURCE_CLASS_POINTER | // Touchscreens and mice (w/o pointer capture)
                            InputDevice.SOURCE_CLASS_POSITION | // Touchpads
                            InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            );
        }

        notificationOverlayView = findViewById(R.id.notificationOverlay);

        performanceOverlayView = findViewById(R.id.performanceOverlay);

        performanceOverlayLite = findViewById(R.id.performanceOverlayLite);

        performanceOverlayBig = findViewById(R.id.performanceOverlayBig);
        channelDisabledHintView = findViewById(R.id.channelDisabledHint);

        if (prefConfig.shouldDisableControl) {
            inputCaptureProvider = new NullCaptureProvider();
        } else {
            inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            streamView.setOnCapturedPointerListener(new View.OnCapturedPointerListener() {
                @Override
                public boolean onCapturedPointer(View view, MotionEvent motionEvent) {
//                    LimeLog.info("onCapturedPointer="+motionEvent.toString());
//                    LimeLog.info("onCapturedPointer-Device="+motionEvent.getDevice().toString());
                    return handleMotionEvent(view, motionEvent);
                }
            });
        }

        // Warn the user if they're on a metered connection
        ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean isMetered = connMgr.isActiveNetworkMetered();
        if (isMetered) {
            displayTransientMessage(getResources().getString(R.string.conn_metered));
        }

        // Make sure Wi-Fi is fully powered up
        WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            highPerfWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Moonlight High Perf Lock");
            highPerfWifiLock.setReferenceCounted(false);
            highPerfWifiLock.acquire();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lowLatencyWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Moonlight Low Latency Lock");
                lowLatencyWifiLock.setReferenceCounted(false);
                lowLatencyWifiLock.acquire();
            }
        } catch (SecurityException e) {
            // Some Samsung Galaxy S10+/S10e devices throw a SecurityException from
            // WifiLock.acquire() even though we have android.permission.WAKE_LOCK in our manifest.
            e.printStackTrace();
        }

        appName = Game.this.getIntent().getStringExtra(EXTRA_APP_NAME);
        pcName = Game.this.getIntent().getStringExtra(EXTRA_PC_NAME);

        host = Game.this.getIntent().getStringExtra(EXTRA_HOST);
        port = Game.this.getIntent().getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT);
        httpsPort = Game.this.getIntent().getIntExtra(EXTRA_HTTPS_PORT, 0); // 0 is treated as unknown
        appUUID = Game.this.getIntent().getStringExtra(EXTRA_APP_UUID);
        appId = Game.this.getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        uniqueId = Game.this.getIntent().getStringExtra(EXTRA_UNIQUEID);
        vDisplay = Game.this.getIntent().getBooleanExtra(EXTRA_VDISPLAY, false);
        serverCommands = Game.this.getIntent().getStringArrayListExtra(EXTRA_SERVER_COMMANDS);
        boolean appSupportsHdr = Game.this.getIntent().getBooleanExtra(EXTRA_APP_HDR, false);
        byte[] derCertData = Game.this.getIntent().getByteArrayExtra(EXTRA_SERVER_CERT);

        app = new NvApp(appName != null ? appName : "app", appUUID, appId, appSupportsHdr);

        try {
            if (derCertData != null) {
                serverCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(derCertData));

                httpConn = new NvHTTP(new ComputerDetails.AddressTuple(host, port), httpsPort, uniqueId, serverCert, PlatformBinding.getCryptoProvider(this));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish();
            return;
        }

        // Initialize the MediaCodec helper before creating the decoder
        GlPreferences glPrefs = GlPreferences.readPreferences(this);
        MediaCodecHelper.initialize(this, glPrefs.glRenderer);

        // Check if the user has enabled HDR
        boolean willStreamHdr = false;
        if (prefConfig.enableHdr) {
            // Start our HDR checklist
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Display display = getWindowManager().getDefaultDisplay();
                Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();

                // We must now ensure our display is compatible with HDR10
                if (hdrCaps != null) {
                    // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                    for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            willStreamHdr = true;
                            break;
                        }
                    }
                }

                if (!willStreamHdr) {
                    // Nope, no HDR for us :(
                    Toast.makeText(this, "Display does not support HDR10", Toast.LENGTH_LONG).show();
                }
            }
            else {
                Toast.makeText(this, "HDR requires Android 7.0 or later", Toast.LENGTH_LONG).show();
            }
        }

        // Check if the user has enabled performance stats overlay
        if (prefConfig.enablePerfOverlay) {
            performanceOverlayView.setVisibility(View.VISIBLE);
            if(prefConfig.enablePerfOverlayLite){
                performanceOverlayLite.setVisibility(View.VISIBLE);
                if(prefConfig.enablePerfOverlayLiteDialog){
                    performanceOverlayLite.setOnClickListener(v -> showGameMenu(null));
                }
            }else{
                performanceOverlayBig.setVisibility(View.VISIBLE);
            }
        }

        if (prefConfig.shouldDisableVideo && prefConfig.shouldDisableAudio && prefConfig.shouldDisableControl) {
            channelDisabledHintView.setVisibility(View.VISIBLE);
            channelDisabledHintView.setText("[ NO CHANNEL ]");
        }

        if (prefConfig.shouldDisableVideo && prefConfig.shouldDisableAudio && !prefConfig.shouldDisableControl) {
            channelDisabledHintView.setVisibility(View.VISIBLE);
            channelDisabledHintView.setText("[ CONTROL ONLY ]");
        }

        if (prefConfig.shouldDisableVideo && !prefConfig.shouldDisableAudio && prefConfig.shouldDisableControl) {
            channelDisabledHintView.setVisibility(View.VISIBLE);
            channelDisabledHintView.setText("[ AUDIO ONLY ]");
        }

        if (prefConfig.shouldDisableVideo && !prefConfig.shouldDisableAudio && !prefConfig.shouldDisableControl) {
            channelDisabledHintView.setVisibility(View.VISIBLE);
            channelDisabledHintView.setText("[ AUDIO ONLY ]\n\n\n[ CONTROLLABLE ]");
        }

        decoderRenderer = new MediaCodecDecoderRenderer(
                this,
                prefConfig,
                new CrashListener() {
                    @Override
                    public void notifyCrash(Exception e) {
                        // The MediaCodec instance is going down due to a crash
                        // let's tell the user something when they open the app again

                        // We must use commit because the app will crash when we return from this function
                        tombstonePrefs.edit().putInt("CrashCount", tombstonePrefs.getInt("CrashCount", 0) + 1).commit();
                        reportedCrash = true;
                    }
                },
                tombstonePrefs.getInt("CrashCount", 0),
                connMgr.isActiveNetworkMetered(),
                willStreamHdr,
                shouldInvertDecoderResolution,
                glPrefs.glRenderer,
                this);

        // Don't stream HDR if the decoder can't support it
        if (willStreamHdr && !decoderRenderer.isHevcMain10Hdr10Supported() && !decoderRenderer.isAv1Main10Supported()) {
            willStreamHdr = false;
            Toast.makeText(this, "Decoder does not support HDR10 profile", Toast.LENGTH_LONG).show();
        }

        // Display a message to the user if HEVC was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC && !decoderRenderer.isHevcSupported()) {
            Toast.makeText(this, "No HEVC decoder found", Toast.LENGTH_LONG).show();
        }

        // Display a message to the user if AV1 was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 && !decoderRenderer.isAv1Supported()) {
            Toast.makeText(this, "No AV1 decoder found", Toast.LENGTH_LONG).show();
        }

        // H.264 is always supported
        int supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264;
        if (decoderRenderer.isHevcSupported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265;
            if (willStreamHdr && decoderRenderer.isHevcMain10Hdr10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265_MAIN10;
            }
        }
        if (decoderRenderer.isAv1Supported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
            if (willStreamHdr && decoderRenderer.isAv1Main10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN10;
            }
        }

        int gamepadMask = ControllerHandler.getAttachedControllerMask(this);
        if (!prefConfig.multiController) {
            // Always set gamepad 1 present for when multi-controller is
            // disabled for games that don't properly support detection
            // of gamepads removed and replugged at runtime.
            gamepadMask = 1;
        }
        if (prefConfig.onscreenController) {
            // If we're using OSC, always set at least gamepad 1.
            gamepadMask |= 1;
        }

        // Set to the optimal mode for streaming
        float displayRefreshRate = prepareDisplayForRendering();
        LimeLog.info("Display refresh rate: "+displayRefreshRate);

        // If the user requested frame pacing using a capped FPS, we will need to change our
        // desired FPS setting here in accordance with the active display refresh rate.
        int roundedRefreshRate = Math.round(displayRefreshRate);
        float chosenFrameRate = prefConfig.fps;
        if (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (prefConfig.fps >= roundedRefreshRate) {
                if (prefConfig.fps > roundedRefreshRate + 3) {
                    // Use frame drops when rendering above the screen frame rate
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    LimeLog.info("Using drop mode for FPS > Hz");
                } else if (roundedRefreshRate <= 49) {
                    // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    LimeLog.info("Bogus refresh rate: " + roundedRefreshRate);
                }
                else {
                    chosenFrameRate = roundedRefreshRate - 1;
                    LimeLog.info("Adjusting FPS target for screen to " + chosenFrameRate);
                }
            }
        }

        if (prefConfig.framePacingWarpFactor > 0) {
            chosenFrameRate *= prefConfig.framePacingWarpFactor;
        }

        StreamConfiguration.Builder configBuild = new StreamConfiguration.Builder()
                .setResolution(
                        displayWidth,
                        displayHeight
                )
                .setLaunchRefreshRate(prefConfig.fps)
                .setRefreshRate(chosenFrameRate)
                .setVirtualDisplay(vDisplay)
                .setResolutionScaleFactor(prefConfig.resolutionScaleFactor)
                .setApp(app)
                .setEnableUltraLowLatency(prefConfig.enableUltraLowLatency)
                .setBitrate(isMetered ? prefConfig.meteredBitrate: prefConfig.bitrate)
                .setEnableSops(prefConfig.enableSops)
                .enableLocalAudioPlayback(prefConfig.playHostAudio)
                .setMaxPacketSize(1392)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO) // NvConnection will perform LAN and VPN detection
                .setSupportedVideoFormats(supportedVideoFormats)
                .setAttachedGamepadMask(gamepadMask)
                .setClientRefreshRateX100((int)(displayRefreshRate * 100))
                .setAudioConfiguration(prefConfig.audioConfiguration)
                .setColorSpace(decoderRenderer.getPreferredColorSpace())
                .setColorRange(decoderRenderer.getPreferredColorRange())
                .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
                ;

//        if (prefConfig.shouldLowerProfileWhenDisableVideo) {
        if (prefConfig.shouldDisableVideo && prefConfig.shouldLowerProfileWhenDisableVideo) {
            configBuild = configBuild.setResolution(144, 108);
//            configBuild = configBuild.setLaunchRefreshRate(2);
//            configBuild = configBuild.setRefreshRate(2);
            configBuild = configBuild.setBitrate(222); // is magic, to enable high-quality audio, please search in code
        }

        if (prefConfig.shouldDisableVideo && prefConfig.shouldDisableAudio) {
            configBuild = configBuild.setBitrate(120);
        }

        if (prefConfig.shouldDisableAudio) {
            // configBuild = configBuild.setAudioConfiguration(new MoonBridge.AudioConfiguration(1, 0x1));
        }

        StreamConfiguration config = configBuild.build();

        // Initialize the connection
        conn = new NvConnection(getApplicationContext(),
                new ComputerDetails.AddressTuple(host, port),
                httpsPort, uniqueId, config,
                PlatformBinding.getCryptoProvider(this), serverCert);
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);
        keyboardTranslator = new KeyboardTranslator(prefConfig);

        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(keyboardTranslator, null);

        // Initialize touch contexts
        String mouseMode = PreferenceManager.getDefaultSharedPreferences(this).getString("mouse_mode_list", "0");
        applyMouseMode(Integer.parseInt(mouseMode));

        /* MERGE_ARTEMIS: TODO: CHECK HERE */
        // Initialize trackpad contexts
        for (int i = 0; i < trackpadContextMap.length; i++) {
            trackpadContextMap[i] = new TrackpadContext(conn, i, prefConfig.trackpadSwapAxis, prefConfig.trackpadSensitivityX, prefConfig.trackpadSensitivityY);
        }

        if (prefConfig.onscreenController) {
            // create virtual onscreen controller
            if (prefConfig.hideOSCWhenHasGamepad) {
                if (!controllerHandler.hasController()) {
                    initVirtualController();
                }
            } else {
                initVirtualController();
            }
        }

        //特殊按键屏幕布局
        if(prefConfig.enableKeyboard){
            initKeyboardController();
        }

        if (!decoderRenderer.isAvcSupported()) {
            if (spinner != null) {
                spinner.dismiss();
                spinner = null;
            }

            // If we can't find an AVC decoder, we can't proceed
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
                    "This device or ROM doesn't support hardware accelerated H.264 playback.", true);
            return;
        }

        // The connection will be started when the surface gets created
        streamView.getHolder().addCallback(this);

        //外接显示器模式
        if(prefConfig.enableExDisplay){
            showSecondScreen();
        }

        gameMenuCallbacks = new GameMenu(this, conn);
        
        floatingMenuButton = findViewById(R.id.floatingMenuButton);
        updateFloatingButtonVisibility(prefConfig.enableBackMenu && prefConfig.enableFloatingButton);
        initFloatingButton();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initFloatingButton() {
        // Touch listener for drag and click
        if (floatingMenuButton != null) {
            floatingMenuButton.setOnTouchListener((view, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        floatingButtonStartX = event.getRawX();
                        floatingButtonStartY = event.getRawY();
                        floatingButtonDX = view.getX() - event.getRawX();
                        floatingButtonDY = view.getY() - event.getRawY();
                        isButtonMoving = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float newX = event.getRawX() + floatingButtonDX;
                        float newY = event.getRawY() + floatingButtonDY;

                        // Check if it's a move or just a tap
                        if (Math.abs(event.getRawX() - floatingButtonStartX) > CLICK_ACTION_THRESHOLD ||
                                Math.abs(event.getRawY() - floatingButtonStartY) > CLICK_ACTION_THRESHOLD) {
                            isButtonMoving = true;
                        }

                        // Ensure the button stays within screen bounds
                        if (newX < 0) newX = 0;
                        if (newY < 0) newY = 0;

                        int maxOffsetX = getWindow().getDecorView().getWidth() - view.getWidth();
                        if (newX > maxOffsetX) {
                            newX = maxOffsetX;
                        }

                        int maxOffsetY = getWindow().getDecorView().getHeight() - view.getHeight();
                        if (newY > maxOffsetY) {
                            newY = maxOffsetY;
                        }

                        view.setX(newX);
                        view.setY(newY);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isButtonMoving) {
                            // It's a click event, show menu
                            showGameMenu(null);
                        }
                        isButtonMoving = false;
                        return true;
                    default:
                        return false;
                }
            });
        }
    }

    private void initKeyboardController(){
        keyBoardController = new KeyBoardController(conn,(FrameLayout)rootView, this);
        keyBoardController.refreshLayout();
        keyBoardController.show();
    }


    private void initVirtualController(){
        virtualController = new VirtualController(controllerHandler, (FrameLayout)rootView, this);
        virtualController.refreshLayout();
        virtualController.show();
    }

    private void initkeyBoardLayoutController(){
        keyBoardLayoutController = new KeyBoardLayoutController((FrameLayout)rootView, this, prefConfig);
        keyBoardLayoutController.refreshLayout();
        keyBoardLayoutController.show();
    }

    //显示隐藏虚拟特殊按键
    public void showHideKeyboardController(){
        if(keyBoardController==null){
            initKeyboardController();
            return;
        }
        keyBoardController.toggleVisibility();
    }

    public void showHidekeyBoardLayoutController(){
        if(keyBoardLayoutController==null){
            initkeyBoardLayoutController();
            return;
        }
        keyBoardLayoutController.toggleVisibility();
    }

    //显示隐藏虚拟手柄控制器
    public void showHideVirtualController(){
        if(virtualController==null){
            initVirtualController();
            prefConfig.onscreenController=true;
            return;
        }
        prefConfig.onscreenController= virtualController.switchShowHide() != 0;
    }

    private void setPreferredOrientationForCurrentDisplay() {
        // shunf4 mod: shunf4 old logic, commented out
//        if (prefConfig.shouldUserRotateLandscapeAtStart) {
//            try {
//                Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, 1);
//            } catch (Throwable t) {
//                Log.w(Game.class.getSimpleName(), Log.getStackTraceString(t));
//                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
//
//                if (clipboard != null) {
//                    ClipData clip = ClipData.newPlainText("text", "appops set com.limelight.shunf4_mod WRITE_SETTINGS allow; appops set com.limelight.shunf4_mod WRITE_SECURE_SETTINGS allow; pm grant com.limelight.shunf4_mod android.permission.WRITE_SETTINGS; pm grant com.limelight.shunf4_mod android.permission.WRITE_SECURE_SETTINGS; ");
//                    clipboard.setPrimaryClip(clip);
//                }
//
//                Toast.makeText(this, "Rotate: system denied. Copied grant command to clipboard.", Toast.LENGTH_LONG).show();
//            }
//        }
//        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
////        Handler h = new Handler();
////        h.postDelayed(new Runnable() {
////            @Override
////            public void run() {
////                // setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
////                // setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
////            }
////        }, 3000);
//        return;
//    }
//
//    private void setPreferredOrientationForCurrentDisplay___unusedunused() {
        Display display = getWindowManager().getDefaultDisplay();

        // For semi-square displays, we use more complex logic to determine which orientation to use (if any)
        if (PreferenceConfiguration.isSquarishScreen(display)) {
            int desiredOrientation = Configuration.ORIENTATION_UNDEFINED;

            // OSC doesn't properly support portrait displays, so don't use it in portrait mode by default
            if (prefConfig.onscreenController) {
                desiredOrientation = Configuration.ORIENTATION_LANDSCAPE;
            }

            // For native resolution, we will lock the orientation to the one that matches the specified resolution
            if (PreferenceConfiguration.isNativeResolution(prefConfig.width, prefConfig.height)) {
                if (displayWidth > displayHeight) {
                    desiredOrientation = Configuration.ORIENTATION_LANDSCAPE;
                }
                else {
                    desiredOrientation = Configuration.ORIENTATION_PORTRAIT;
                }
            }

            if (desiredOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
            }
            else if (desiredOrientation == Configuration.ORIENTATION_PORTRAIT) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            }
            else {
                // If we don't have a reason to lock to portrait or landscape, allow any orientation
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
            }
        }
        else {
            // Lock to current orientation
            if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Set requested orientation for possible new screen size
        // setPreferredOrientationForCurrentDisplay();

        if (virtualController != null) {
            // Refresh layout of OSC for possible new screen size
            virtualController.refreshLayout();
        }

        if(keyBoardController != null){
            keyBoardController.refreshLayout();
        }

        if(keyBoardLayoutController != null){
            keyBoardLayoutController.refreshLayout();
        }

        // Hide on-screen overlays in PiP mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isInPictureInPictureMode()) {
                isHidingOverlays = true;

                floatingButtonShown = floatingMenuButton.isShown();

                if (floatingButtonShown) {
                    floatingMenuButton.setVisibility(View.GONE);
                }

                if (virtualController != null) {
                    virtualController.hide();
                }

                if (keyBoardController != null && keyBoardController.shown) {
                    keyBoardController.hide(true);
                }

                if (keyBoardLayoutController != null && keyBoardLayoutController.shown) {
                    keyBoardLayoutController.hide(true);
                }

                hideGameMenu();

                performanceOverlayView.setVisibility(View.GONE);
                notificationOverlayView.setVisibility(View.GONE);

                // Disable sensors while in PiP mode
                controllerHandler.disableSensors();

                // Update GameManager state to indicate we're in PiP (still gaming, but interruptible)
                UiHelper.notifyStreamEnteringPiP(this);
            }
            else {
                isHidingOverlays = false;

                if (floatingButtonShown) {
                    floatingMenuButton.setVisibility(View.VISIBLE);
                }

                // Restore overlays to previous state when leaving PiP

                if (virtualController != null) {
                    virtualController.show();
                }

                if (keyBoardController != null && keyBoardController.shown) {
                    keyBoardController.show();
                }

                if(keyBoardLayoutController!=null && keyBoardLayoutController.shown){
                    keyBoardLayoutController.show();
                }

                if (prefConfig.enablePerfOverlay) {
                    performanceOverlayView.setVisibility(View.VISIBLE);
                }

                notificationOverlayView.setVisibility(requestedNotificationOverlayVisibility);

                // Enable sensors again after exiting PiP
                controllerHandler.enableSensors();

                // Update GameManager state to indicate we're out of PiP (gaming, non-interruptible)
                UiHelper.notifyStreamExitingPiP(this);
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private PictureInPictureParams getPictureInPictureParams(boolean autoEnter) {
        View view;
        Rect hint;
        if (prefConfig.videoScaleMode == PreferenceConfiguration.ScaleMode.FIT && streamView.getScaleX() == 1) {
            view = streamView;
        } else {
            view = (View)rootView;
        }

        int[] viewLocation = new int[2];

        view.getLocationOnScreen(viewLocation);

        int left = viewLocation[0];
        int top = viewLocation[1];
        int width = view.getWidth();
        int height = view.getHeight();
        Rational aspectRatio = new Rational(width, height);
        hint = new Rect(left, top, left + width, top + height);

        PictureInPictureParams.Builder builder =
                new PictureInPictureParams.Builder()
                        .setAspectRatio(aspectRatio)
                        .setSourceRectHint(hint);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter);
            builder.setSeamlessResizeEnabled(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (appName != null) {
                builder.setTitle(appName);
                if (pcName != null) {
                    builder.setSubtitle(pcName);
                }
            }
            else if (pcName != null) {
                builder.setTitle(pcName);
            }
        }

        return builder.build();
    }

    public void updatePipAutoEnter() {
        if (!prefConfig.enablePip) {
            return;
        }

        boolean autoEnter = connected && suppressPipRefCount == 0;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(getPictureInPictureParams(autoEnter));
        }
        else {
            autoEnterPip = autoEnter;
        }
    }

    public void setMetaKeyCaptureState(boolean enabled) {
        // This uses custom APIs present on some Samsung devices to allow capture of
        // meta key events while streaming.
        try {
            Class<?> semWindowManager = Class.forName("com.samsung.android.view.SemWindowManager");
            Method getInstanceMethod = semWindowManager.getMethod("getInstance");
            Object manager = getInstanceMethod.invoke(null);

            if (manager != null) {
                Class<?>[] parameterTypes = new Class<?>[2];
                parameterTypes[0] = ComponentName.class;
                parameterTypes[1] = boolean.class;
                Method requestMetaKeyEventMethod = semWindowManager.getDeclaredMethod("requestMetaKeyEvent", parameterTypes);
                requestMetaKeyEventMethod.invoke(manager, this.getComponentName(), enabled);
            }
            else {
                LimeLog.warning("SemWindowManager.getInstance() returned null");
            }
        } catch (ClassNotFoundException | NoSuchMethodException | InvocationTargetException |
                 IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();

        // PiP is only supported on Oreo and later, and we don't need to manually enter PiP on
        // Android S and later. On Android R, we will use onPictureInPictureRequested() instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (autoEnterPip) {
                try {
                    // This has thrown all sorts of weird exceptions on Samsung devices
                    // running Oreo. Just eat them and close gracefully on leave, rather
                    // than crashing.
                    enterPictureInPictureMode(getPictureInPictureParams(false));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.R)
    public boolean onPictureInPictureRequested() {
        // Enter PiP when requested unless we're on Android 12 which supports auto-enter.
        if (autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(getPictureInPictureParams(false));
        }
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // We can't guarantee the state of modifiers keys which may have
        // lifted while focus was not on us. Clear the modifier state.
        this.modifierFlags = 0;

        // With Android native pointer capture, capture is lost when focus is lost,
        // so it must be requested again when focus is regained.
        inputCaptureProvider.onWindowFocusChanged(hasFocus);
    }

    private boolean isRefreshRateEqualMatch(float refreshRate) {
        return refreshRate >= prefConfig.fps &&
                refreshRate <= prefConfig.fps + 3;
    }

    private boolean isRefreshRateGoodMatch(float refreshRate) {
        return refreshRate >= prefConfig.fps &&
                Math.round(refreshRate) % prefConfig.fps <= 3;
    }

    private boolean shouldIgnoreInsetsForResolution(int width, int height) {
        // Never ignore insets for non-native resolutions
        if (!PreferenceConfiguration.isNativeResolution(width, height)) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display display = getWindowManager().getDefaultDisplay();
            for (Display.Mode candidate : display.getSupportedModes()) {
                // Ignore insets if this is an exact match for the display resolution
                if ((width == candidate.getPhysicalWidth() && height == candidate.getPhysicalHeight()) ||
                        (height == candidate.getPhysicalWidth() && width == candidate.getPhysicalHeight())) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean mayReduceRefreshRate() {
        return prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS ||
                prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED && prefConfig.reduceRefreshRate);
    }

    private float prepareDisplayForRendering() {
        Display display = getWindowManager().getDefaultDisplay();
        WindowManager.LayoutParams windowLayoutParams = getWindow().getAttributes();
        float displayRefreshRate;

        // On M, we can explicitly set the optimal display mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode bestMode = display.getMode();
            boolean isNativeResolutionStream = PreferenceConfiguration.isNativeResolution(prefConfig.width, prefConfig.height);
            boolean refreshRateIsGood = isRefreshRateGoodMatch(bestMode.getRefreshRate());
            boolean refreshRateIsEqual = isRefreshRateEqualMatch(bestMode.getRefreshRate());

            LimeLog.info("Current display mode: "+bestMode.getPhysicalWidth()+"x"+
                    bestMode.getPhysicalHeight()+"x"+bestMode.getRefreshRate());

            for (Display.Mode candidate : display.getSupportedModes()) {
                boolean refreshRateReduced = candidate.getRefreshRate() < bestMode.getRefreshRate();
                boolean resolutionReduced = candidate.getPhysicalWidth() < bestMode.getPhysicalWidth() ||
                        candidate.getPhysicalHeight() < bestMode.getPhysicalHeight();
                boolean resolutionFitsStream = candidate.getPhysicalWidth() >= prefConfig.width &&
                        candidate.getPhysicalHeight() >= prefConfig.height;

                LimeLog.info("Examining display mode: "+candidate.getPhysicalWidth()+"x"+
                        candidate.getPhysicalHeight()+"x"+candidate.getRefreshRate());

                if (candidate.getPhysicalWidth() > 4096 && prefConfig.width <= 4096) {
                    // Avoid resolutions options above 4K to be safe
                    continue;
                }

                // On non-4K streams, we force the resolution to never change unless it's above
                // 60 FPS, which may require a resolution reduction due to HDMI bandwidth limitations,
                // or it's a native resolution stream.
                if (prefConfig.width < 3840 && prefConfig.fps <= 60 && !isNativeResolutionStream) {
                    if (display.getMode().getPhysicalWidth() != candidate.getPhysicalWidth() ||
                            display.getMode().getPhysicalHeight() != candidate.getPhysicalHeight()) {
                        continue;
                    }
                }

                // Make sure the resolution doesn't regress unless if it's over 60 FPS
                // where we may need to reduce resolution to achieve the desired refresh rate.
                if (resolutionReduced && !(prefConfig.fps > 60 && resolutionFitsStream)) {
                    continue;
                }

                if (mayReduceRefreshRate() && refreshRateIsEqual && !isRefreshRateEqualMatch(candidate.getRefreshRate())) {
                    // If we had an equal refresh rate and this one is not, skip it. In min latency
                    // mode, we want to always prefer the highest frame rate even though it may cause
                    // microstuttering.
                    continue;
                }
                else if (refreshRateIsGood) {
                    // We've already got a good match, so if this one isn't also good, it's not
                    // worth considering at all.
                    if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
                        continue;
                    }

                    if (mayReduceRefreshRate()) {
                        // User asked for the lowest possible refresh rate, so don't raise it if we
                        // have a good match already
                        if (candidate.getRefreshRate() > bestMode.getRefreshRate()) {
                            continue;
                        }
                    }
                    else {
                        // User asked for the highest possible refresh rate, so don't reduce it if we
                        // have a good match already
                        if (refreshRateReduced) {
                            continue;
                        }
                    }
                }
                else if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
                    // We didn't have a good match and this match isn't good either, so just don't
                    // reduce the refresh rate.
                    if (refreshRateReduced) {
                        continue;
                    }
                } else {
                    // We didn't have a good match and this match is good. Prefer this refresh rate
                    // even if it reduces the refresh rate. Lowering the refresh rate can be beneficial
                    // when streaming a 60 FPS stream on a 90 Hz device. We want to select 60 Hz to
                    // match the frame rate even if the active display mode is 90 Hz.
                }

                bestMode = candidate;
                refreshRateIsGood = isRefreshRateGoodMatch(candidate.getRefreshRate());
                refreshRateIsEqual = isRefreshRateEqualMatch(candidate.getRefreshRate());
            }

            LimeLog.info("Best display mode: "+bestMode.getPhysicalWidth()+"x"+
                    bestMode.getPhysicalHeight()+"x"+bestMode.getRefreshRate());

            // Only apply new window layout parameters if we've actually changed the display mode
            if (display.getMode().getModeId() != bestMode.getModeId()) {
                // If we only changed refresh rate and we're on an OS that supports Surface.setFrameRate()
                // use that instead of using preferredDisplayModeId to avoid the possibility of triggering
                // bugs that can cause the system to switch from 4K60 to 4K24 on Chromecast 4K.
                if (prefConfig.enforceDisplayMode ||
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        display.getMode().getPhysicalWidth() != bestMode.getPhysicalWidth() ||
                        display.getMode().getPhysicalHeight() != bestMode.getPhysicalHeight()) {
                    // Apply the display mode change
                    windowLayoutParams.preferredDisplayModeId = bestMode.getModeId();
                    getWindow().setAttributes(windowLayoutParams);
                }
                else {
                    LimeLog.info("Using setFrameRate() instead of preferredDisplayModeId due to matching resolution");
                }
            }
            else {
                LimeLog.info("Current display mode is already the best display mode");
            }

            displayRefreshRate = bestMode.getRefreshRate();
        }
        // On L, we can at least tell the OS that we want a refresh rate
        else {
            float bestRefreshRate = display.getRefreshRate();
            for (float candidate : display.getSupportedRefreshRates()) {
                LimeLog.info("Examining refresh rate: "+candidate);

                if (candidate > bestRefreshRate) {
                    // Ensure the frame rate stays around 60 Hz for <= 60 FPS streams
                    if (prefConfig.fps <= 60) {
                        if (candidate >= 63) {
                            continue;
                        }
                    }

                    bestRefreshRate = candidate;
                }
            }

            LimeLog.info("Selected refresh rate: "+bestRefreshRate);
            windowLayoutParams.preferredRefreshRate = bestRefreshRate;
            displayRefreshRate = bestRefreshRate;

            // Apply the refresh rate change
            getWindow().setAttributes(windowLayoutParams);
        }

        // Until Marshmallow, we can't ask for a 4K display mode, so we'll
        // need to hint the OS to provide one.
        boolean aspectRatioMatch = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // We'll calculate whether we need to scale by aspect ratio. If not, we'll use
            // setFixedSize so we can handle 4K properly. The only known devices that have
            // >= 4K screens have exactly 4K screens, so we'll be able to hit this good path
            // on these devices. On Marshmallow, we can start changing to 4K manually but no
            // 4K devices run 6.0 at the moment.
            Point screenSize = new Point(0, 0);
            display.getSize(screenSize);

            double screenAspectRatio = ((double)screenSize.y) / screenSize.x;
            double streamAspectRatio = ((double)displayHeight) / displayWidth;
            if (Math.abs(screenAspectRatio - streamAspectRatio) < 0.001) {
                LimeLog.info("Stream has compatible aspect ratio with output display");
                aspectRatioMatch = true;
            }
        }

        // Don't do setFixedSize since it might not update the view dimensions correctly when entering PiP mode
        if (!(prefConfig.videoScaleMode == PreferenceConfiguration.ScaleMode.STRETCH || aspectRatioMatch)) {
            // Set the surface to scale based on the aspect ratio of the stream
            streamView.setDesiredAspectRatio((double)displayWidth / (double)displayHeight);
            streamView.setFillDisplay(prefConfig.videoScaleMode == PreferenceConfiguration.ScaleMode.FILL);
            LimeLog.info("surfaceChanged-->"+(double)displayWidth / (double)displayHeight);
            LimeLog.info("scaleMode-->"+prefConfig.videoScaleMode);
            ViewGroup.LayoutParams lp = streamView.getLayoutParams();
            if ((
                    prefConfig.shouldUseShader0 ||
                            prefConfig.shouldUseShader1 ||
                prefConfig.shouldUseShader2 ||
                prefConfig.shouldUseShader3 ||
                prefConfig.shouldUseShader4 ||
                prefConfig.shouldUseShader5 ||
                prefConfig.shouldUseShader6 ||
                        prefConfig.shouldUseShader7 ||
                        prefConfig.shouldUseShader8 ||
                prefConfig.shouldUseShader9
            )) {
                lp.width = prefConfig.width;
                lp.height = prefConfig.height;
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            streamView.setLayoutParams(lp);
        }

        // Set the desired refresh rate that will get passed into setFrameRate() later
        desiredRefreshRate = displayRefreshRate;

        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // TVs may take a few moments to switch refresh rates, and we can probably assume
            // it will be eventually activated.
            // TODO: Improve this
            return displayRefreshRate;
        }
        else {
            // Use the lower of the current refresh rate and the selected refresh rate.
            // The preferred refresh rate may not actually be applied (ex: Battery Saver mode).
            return Math.min(getWindowManager().getDefaultDisplay().getRefreshRate(), displayRefreshRate);
        }
    }

    @SuppressLint("InlinedApi")
    private final Runnable hideSystemUi = new Runnable() {
        @Override
        public void run() {
            // TODO: Do we want to use WindowInsetsController here on R+ instead of
            // SYSTEM_UI_FLAG_IMMERSIVE_STICKY? They seem to do the same thing as of S...

            // In multi-window mode on N+, we need to drop our layout flags or we'll
            // be drawing underneath the system UI.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode()) {
                Game.this.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
            else {
                // Use immersive mode
                    if (prefConfig.isCurrDeviceLikeOnyx) {
                        Game.this.getWindow().getDecorView().setSystemUiVisibility(
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
//                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
//                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                    } else {
                    Game.this.getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                    }
            }
        }
    };

    private void hideSystemUi(int delay) {
        Handler h = getWindow().getDecorView().getHandler();
        if (h != null) {
            h.removeCallbacks(hideSystemUi);
            h.postDelayed(hideSystemUi, delay);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.N)
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);

        // In multi-window, we don't want to use the full-screen layout
        // flag. It will cause us to collide with the system UI.
        // This function will also be called for PiP so we can cover
        // that case here too.
        if (isInMultiWindowMode) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decoderRenderer.notifyVideoBackground();
        }
        else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decoderRenderer.notifyVideoForeground();
        }

        // Correct the system UI visibility flags
        hideSystemUi(50);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        instance = null;

        if(presentation!=null){
            presentation.dismiss();
        }

        if (prefConfig.shouldUserRotateLandscapeAtStart) {
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, 0);
            } catch (Throwable t) {
                Log.w(Game.class.getSimpleName(), Log.getStackTraceString(t));
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText("text", "appops set com.limelight.shunf4_mod WRITE_SETTINGS allow; appops set com.limelight.shunf4_mod WRITE_SECURE_SETTINGS allow; pm grant com.limelight.shunf4_mod android.permission.WRITE_SETTINGS; pm grant com.limelight.shunf4_mod android.permission.WRITE_SECURE_SETTINGS; ");
                    clipboard.setPrimaryClip(clip);
                }

                Toast.makeText(this, "Rotate: system denied. Copied grant command to clipboard.", Toast.LENGTH_LONG).show();
            }
        }

        if (controllerHandler != null) {
            controllerHandler.destroy();
        }
        if (keyboardTranslator != null) {
            InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
            inputManager.unregisterInputDeviceListener(keyboardTranslator);
        }

        if (lowLatencyWifiLock != null) {
            lowLatencyWifiLock.release();
        }
        if (highPerfWifiLock != null) {
            highPerfWifiLock.release();
        }

        if (connectedToUsbDriverService) {
            // Unbind from the discovery service
            unbindService(usbDriverServiceConnection);
        }

        // Destroy the capture provider
        inputCaptureProvider.destroy();
    }

    @Override
    protected void onPause() {
        if (isFinishing()) {
            // Stop any further input device notifications before we lose focus (and pointer capture)
            if (controllerHandler != null) {
                controllerHandler.stop();
            }

            // Ungrab input to prevent further input device notifications
            setInputGrabState(false);
        }

        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (virtualController != null) {
            virtualController.hide();
        }
        if (keyBoardController != null) {
            keyBoardController.hide();
        }

        if(keyBoardLayoutController!=null){
            keyBoardLayoutController.hide();
        }

        if (conn != null) {
            int videoFormat = decoderRenderer.getActiveVideoFormat();

            displayedFailureDialog = true;
            stopConnection();
            String message = null;
            String selectedVideoFormat = "";

            int averageEndToEndLat = decoderRenderer.getAverageEndToEndLatency();
            int averageDecoderLat = decoderRenderer.getAverageDecoderLatency();

            if (averageEndToEndLat > 0) {
                message = getResources().getString(R.string.conn_client_latency) + " " + averageEndToEndLat + " ms";
                if (averageDecoderLat > 0) {
                    message += " (" + getResources().getString(R.string.conn_client_latency_hw) + " " + averageDecoderLat + " ms)";
                }
            } else if (averageDecoderLat > 0) {
                message = getResources().getString(R.string.conn_hardware_latency) + " " + averageDecoderLat + " ms";
            }

            // Add the video codec to the post-stream toast
            selectedVideoFormat += " [";

            if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                selectedVideoFormat += "H.264";
            } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                selectedVideoFormat += "HEVC";
            } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                selectedVideoFormat += "AV1";
            }
            else {
                selectedVideoFormat += "UNKNOWN";
            }

            if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
                selectedVideoFormat += " HDR";
            }

            selectedVideoFormat += "]";

            if (message != null) {
                message += selectedVideoFormat;
            }

            if (message != null) {
                if (prefConfig.enableLatencyToast) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
            }

            // Clear the tombstone count if we terminated normally
            if (!reportedCrash && tombstonePrefs.getInt("CrashCount", 0) != 0) {
                tombstonePrefs.edit()
                        .putInt("CrashCount", 0)
                        .putInt("LastNotifiedCrashCount", 0)
                        .apply();
            }
            if(prefConfig.enablePerfLogging && decoderRenderer.performanceWasTracked()) {
                new PerformanceDataTracker().savePerformanceStatistics(
                        getBaseContext(),
                        Build.MODEL,
                        Build.VERSION.SDK_INT + "",
                        BuildConfig.VERSION_NAME,
                        selectedVideoFormat,
                        decoderRenderer.getMinDecoderLatency(),
                        decoderRenderer.getMinDecoderLatencyFullLog(),
                        String.valueOf((prefConfig.bitrate / 1000)),
                    displayWidth + "x" + displayHeight,
                    prefConfig.fps + " hz",
                    decoderRenderer.getAverageDecoderLatency() + " ms",
                        PreferenceConfiguration.getSelectedFramePacingName(getBaseContext()),
                        formatCurrentTime(System.currentTimeMillis())
                );
            }

        }

        finish();
    }

    public static String formatCurrentTime(long currentTimeMillis) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Date date = new Date(currentTimeMillis);
        return dateFormat.format(date);
    }

    private void setInputGrabState(boolean grab) {
        // Grab/ungrab the mouse cursor
        if (grab) {
            inputCaptureProvider.enableCapture();

            // Enabling capture may hide the cursor again, so
            // we will need to show it again.
            if (cursorVisible) {
                inputCaptureProvider.showCursor();
            }
        }
        else {
            inputCaptureProvider.disableCapture();
        }

        // Grab/ungrab system keyboard shortcuts
        setMetaKeyCaptureState(grab);

        grabbedInput = grab;
    }

    private final Runnable toggleGrab = new Runnable() {
        @Override
        public void run() {
            setInputGrabState(!grabbedInput);
        }
    };

    // Returns true if the key stroke was consumed
    private boolean handleSpecialKeys(int androidKeyCode, boolean down) {
        int modifierMask = 0;
        int nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN;

        if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_CTRL;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_SHIFT;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_ALT;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_META_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_META_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_META;
        }
        else {
            nonModifierKeyCode = androidKeyCode;
        }

        if (down) {
            this.modifierFlags |= modifierMask;
        }
        else {
            this.modifierFlags &= ~modifierMask;
        }

        // Handle the special combos on the key up
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode == androidKeyCode) {
                // If this is a key up for the special key itself, eat that because the host never saw the original key down
                return true;
            }
            else if (modifierFlags != 0) {
                // While we're waiting for modifiers to come up, eat all key downs and allow all key ups to pass
                return down;
            }
            else {
                // When all modifiers are up, perform the special action
                switch (specialKeyCode) {
                    // Toggle input grab
                    case KeyEvent.KEYCODE_Z:
                        Handler h = getWindow().getDecorView().getHandler();
                        if (h != null) {
                            h.postDelayed(toggleGrab, 250);
                        }
                        break;

                    // Quit
                    case KeyEvent.KEYCODE_Q:
                        finish();
                        break;

                    // Toggle cursor visibility
                    case KeyEvent.KEYCODE_C:
                        if (!grabbedInput) {
                            inputCaptureProvider.enableCapture();
                            grabbedInput = true;
                        }
                        cursorVisible = !cursorVisible;
                        if (cursorVisible) {
                            inputCaptureProvider.showCursor();
                        } else {
                            inputCaptureProvider.hideCursor();
                        }
                        break;

                    default:
                        break;
                }

                // Reset special key state
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
                waitingForAllModifiersUp = false;
            }
        }
        // Check if Ctrl+Alt+Shift is down when a non-modifier key is pressed
        else if ((modifierFlags & (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT)) ==
                (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT) &&
                (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)) {
            switch (androidKeyCode) {
                case KeyEvent.KEYCODE_Z:
                case KeyEvent.KEYCODE_Q:
                case KeyEvent.KEYCODE_C:
                    // Remember that a special key combo was activated, so we can consume all key
                    // events until the modifiers come up
                    specialKeyCode = androidKeyCode;
                    waitingForAllModifiersUp = true;
                    return true;

                default:
                    // This isn't a special combo that we consume on the client side
                    return false;
            }
        }

        // Not a special combo
        return false;
    }

    // We cannot simply use modifierFlags for all key event processing, because
    // some IMEs will not generate real key events for pressing Shift. Instead
    // they will simply send key events with isShiftPressed() returning true,
    // and we will need to send the modifier flag ourselves.
    private byte getModifierState(KeyEvent event) {
        // Start with the global modifier state to ensure we cover the case
        // detailed in https://github.com/moonlight-stream/moonlight-android/issues/840
        byte modifier = getModifierState();
        if (event.isShiftPressed()) {
            modifier |= KeyboardPacket.MODIFIER_SHIFT;
        }
        if (event.isCtrlPressed()) {
            modifier |= KeyboardPacket.MODIFIER_CTRL;
        }
        if (event.isAltPressed()) {
            modifier |= KeyboardPacket.MODIFIER_ALT;
        }
        if (event.isMetaPressed()) {
            modifier |= KeyboardPacket.MODIFIER_META;
        }
        return modifier;
    }

    private byte getModifierState() {
        return (byte) modifierFlags;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKeyDown(event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean handleKeyDown(KeyEvent event) {
        if (prefConfig.isCurrDeviceLikeOnyx && ((event.getFlags() == 0xC8) && event.getSource() == 0x101 && (event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN || event.getKeyCode() == KeyEvent.KEYCODE_BACK))) {
            // Onyx BOOX nav back button long press
            toggleKeyboard();
            return true;
        }

        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        int deviceId = event.getDeviceId();
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false;
        }

        // Onyx BOOX sends nav bar back button with NO FLAG_VIRTUAL_HARD_KEY flag, with non-standard event source 0x12345678
        if (prefConfig.isCurrDeviceLikeOnyx && ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) == 0 && event.getSource() == 0x12345678)) {
            return false;
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click. This event WILL repeat if
        // the right mouse button is held down, so we ignore those.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE)) {
            if (prefConfig.shouldDisableControl) {
                return false;
            }
        }
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        boolean handled = false;

        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonDown(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            // Let this method take duplicate key down events
            if (handleSpecialKeys(event.getKeyCode(), true)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            // We'll send it as a raw key event if we have a key mapping, otherwise we'll send it
            // as UTF-8 text (if it's a printable character).
            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getScanCode(), deviceId);
            if (translated == 0) {
                if (prefConfig.backAsMeta && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    translated = 0x5b; // Meta key
                } else {
                    // Make sure it has a valid Unicode representation and it's not a dead character
                    // (which we don't support). If those are true, we can send it as UTF-8 text.
                    //
                    // NB: We need to be sure this happens before the getRepeatCount() check because
                    // UTF-8 events don't auto-repeat on the host side.
                    int unicodeChar = event.getUnicodeChar();
                    if ((unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0) {
                        conn.sendUtf8Text(""+(char)unicodeChar);
                        return true;
                    }

                    return false;
                }
            }

            // Eat repeat down events
            if (event.getRepeatCount() > 0) {
                return true;
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), deviceId) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleKeyUp(event) || super.onKeyUp(keyCode, event);
    }

    public List<Function<Boolean, Boolean>> prefShaderConfigFunctions = Arrays.<Function<Boolean, Boolean>>asList(
            (newValue) -> { Boolean oldValue = prefConfig.shouldUseShader0; prefConfig.shouldUseShader0 = newValue != null ? newValue : oldValue; return oldValue; },
            (newValue) -> { Boolean oldValue = prefConfig.shouldUseShader1; prefConfig.shouldUseShader1 = newValue != null ? newValue : oldValue; return oldValue; },
            (newValue) -> { Boolean oldValue = prefConfig.shouldUseShader2; prefConfig.shouldUseShader2 = newValue != null ? newValue : oldValue; return oldValue; },
            (newValue) -> { Boolean oldValue = prefConfig.shouldUseShader3; prefConfig.shouldUseShader3 = newValue != null ? newValue : oldValue; return oldValue; },
            (newValue) -> { Boolean oldValue = prefConfig.shouldUseShader4; prefConfig.shouldUseShader4 = newValue != null ? newValue : oldValue; return oldValue; },
            (newValue) -> { Boolean oldValue = prefConfig.shouldUseShader5; prefConfig.shouldUseShader5 = newValue != null ? newValue : oldValue; return oldValue; },
            (newValue) -> { Boolean oldValue = prefConfig.shouldUseShader6; prefConfig.shouldUseShader6 = newValue != null ? newValue : oldValue; return oldValue; },
            (newValue) -> { Boolean oldValue = prefConfig.shouldUseShader7; prefConfig.shouldUseShader7 = newValue != null ? newValue : oldValue; return oldValue; },
            (newValue) -> { Boolean oldValue = prefConfig.shouldUseShader8; prefConfig.shouldUseShader8 = newValue != null ? newValue : oldValue; return oldValue; },
            (newValue) -> { Boolean oldValue = prefConfig.shouldUseShader9; prefConfig.shouldUseShader9 = newValue != null ? newValue : oldValue; return oldValue; }
    );

    public static Map<Integer, String> shaderDescMap = new HashMap<>();
    static {
        shaderDescMap.put(0, "Unchanged");
        shaderDescMap.put(1, "Grayscale");
        shaderDescMap.put(2, "8-Level Gray");
        shaderDescMap.put(3, "8-Level BW Dotted");
        shaderDescMap.put(4, "White++++");
        shaderDescMap.put(5, "White+++");
        shaderDescMap.put(6, "White+");
        shaderDescMap.put(7, "Medium");
        shaderDescMap.put(8, "Black+");
        shaderDescMap.put(9, "Black++++");
    }

    @Override
    public boolean handleKeyUp(KeyEvent event) {
        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        int deviceId = event.getDeviceId();
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (prefConfig.isCurrDeviceLikeOnyx && event.getUnicodeChar() == '{') {
                int currentSelected = -1;
                for (int i = prefShaderConfigFunctions.size() - 1; i >= 0; i--) {
                    if (Boolean.TRUE.equals(prefShaderConfigFunctions.get(i).apply(null))) {
                        currentSelected = i;
                        break;
                    }
                }
                if (currentSelected != -1) {
                    prefShaderConfigFunctions.get(currentSelected).apply(false);
                }
                int targetShader = currentSelected <= 0 ? (prefShaderConfigFunctions.size() - 1) : (currentSelected - 1);
                prefShaderConfigFunctions.get(targetShader).apply(true);
                decoderRenderer.shunf4ModReload();
                Toast.makeText(this, "Shader " + targetShader + (shaderDescMap.get(targetShader) != null ? (" (" + shaderDescMap.get(targetShader) + ")") : ""), Toast.LENGTH_SHORT).show();
            }

            if (prefConfig.isCurrDeviceLikeOnyx && event.getUnicodeChar() == '}') {
                int currentSelected = -1;
                for (int i = prefShaderConfigFunctions.size() - 1; i >= 0; i--) {
                    if (Boolean.TRUE.equals(prefShaderConfigFunctions.get(i).apply(null))) {
                        currentSelected = i;
                        break;
                    }
                }
                if (currentSelected != -1) {
                    prefShaderConfigFunctions.get(currentSelected).apply(false);
                }
                int targetShader = currentSelected >= (prefShaderConfigFunctions.size() - 1) ? (0) : (currentSelected + 1);
                prefShaderConfigFunctions.get(targetShader).apply(true);
                decoderRenderer.shunf4ModReload();
                Toast.makeText(this, "Shader " + targetShader + (shaderDescMap.get(targetShader) != null ? (" (" + shaderDescMap.get(targetShader) + ")") : ""), Toast.LENGTH_SHORT).show();
            }
        }

        // Onyx BOOX sends nav bar back button with NO FLAG_VIRTUAL_HARD_KEY flag, with non-standard event source 0x12345678
        if (prefConfig.isCurrDeviceLikeOnyx && ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) == 0 && event.getSource() == 0x12345678)) {
            return false;
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE)) {
            if (prefConfig.shouldDisableControl) {
                return false;
            }
        }
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        boolean handled = false;
        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonUp(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            if (handleSpecialKeys(event.getKeyCode(), false)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getScanCode(), deviceId);
            if (translated == 0) {
                if (prefConfig.backAsMeta && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    translated = 0x5b; // Meta key
                } else {
                    // If we sent this event as UTF-8 on key down, also report that it was handled
                    // when we get the key up event for it.
                    int unicodeChar = event.getUnicodeChar();
                    return (unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0;
                }
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), deviceId) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        return handleKeyMultiple(event) || super.onKeyMultiple(keyCode, repeatCount, event);
    }

    private boolean handleKeyMultiple(KeyEvent event) {
        // We can receive keys from a software keyboard that don't correspond to any existing
        // KEYCODE value. Android will give those to us as an ACTION_MULTIPLE KeyEvent.
        //
        // Despite the fact that the Android docs say this is unused since API level 29, these
        // events are still sent as of Android 13 for the above case.
        //
        // For other cases of ACTION_MULTIPLE, we will not report those as handled so hopefully
        // they will be passed to us again as regular singular key events.
        if (event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN || event.getCharacters() == null) {
            return false;
        }

        conn.sendUtf8Text(event.getCharacters());
        return true;
    }

    public boolean handleFocusChange(boolean hasFocus) {
        if (connected && prefConfig.smartClipboardSync) {
            if (hasFocus) {
                return sendClipboard(false);
            } else {
                return getClipboard(0);
            }
        }

        return false;
    }

    // Method to get clipboard content
    private String getClipboardContent(boolean force) {
        // Check if there is any clipboard data
        if (clipboardManager.hasPrimaryClip()) {
            ClipDescription clipDescription = clipboardManager.getPrimaryClipDescription();
            if (!force && clipDescription != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    PersistableBundle extras = clipDescription.getExtras();
                    if (extras != null && extras.getBoolean(CLIPBOARD_IDENTIFIER)) {
                        // We're getting the clipboard data we just set/read a while ago
                        return null;
                    }
                } else {
                    CharSequence clipLabel = clipDescription.getLabel();
                    if (clipLabel != null && clipLabel.equals(CLIPBOARD_IDENTIFIER)) {
                        // We're getting the clipboard data we set a while ago
                        return null;
                    }
                }
            }

            ClipData clipData = clipboardManager.getPrimaryClip();

            if (clipData != null && clipData.getItemCount() > 0) {
                // Get the first item from the clipboard data
                ClipData.Item item = clipData.getItemAt(0);

                // Mark the clip as visited
                if (clipDescription != null) {
                    ClipData clonedClip = cloneClipData(clipDescription, item);
                    clipboardManager.setPrimaryClip(clonedClip);
                }

                // Get the text data from the clipboard item
                CharSequence clipText = item.getText();
                if (clipText == null) {
                    return  null;
                }
                return clipText.toString();
            }
        }

        return null;
    }

    private static @NonNull ClipData cloneClipData(ClipDescription clipDescription, ClipData.Item item) {
        ClipDescription clonedDescription = new ClipDescription(clipDescription);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PersistableBundle extras = clipDescription.getExtras();
            if (extras == null) {
                extras = new PersistableBundle();
            }
            extras.putBoolean(CLIPBOARD_IDENTIFIER, true);
            clonedDescription.setExtras(extras);
        }

        return new ClipData(clonedDescription, item);
    }

    public boolean sendClipboard(boolean force) {
        if (httpConn == null) {
            LimeLog.warning("httpConn not ready, cannot send clipboard!");
            return false;
        }

        String clipboardText = getClipboardContent(force);
        if (clipboardText != null) {
            new Thread() {
                public void run() {
                    try {
                        if (!httpConn.sendClipboard(clipboardText)) {
                            if (prefConfig.smartClipboardSyncToast) {
                                Game.this.runOnUiThread(() -> Toast.makeText(Game.this, getString(R.string.clipboard_sync_unsupported), Toast.LENGTH_SHORT).show());
                            }
                        } else {
                            if (prefConfig.smartClipboardSyncToast) {
                                Game.this.runOnUiThread(() -> Toast.makeText(Game.this, getString(R.string.send_clipboard_success), Toast.LENGTH_SHORT).show());
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (prefConfig.smartClipboardSyncToast) {
                            Game.this.runOnUiThread(() -> Toast.makeText(Game.this, getString(R.string.send_clipboard_failed) + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }
                }
            }.start();

            return true;
        }

        return false;
    }

    public boolean getClipboard(int delay) {
        if (httpConn == null) {
            LimeLog.warning("httpConn not ready, cannot get clipboard!");
            return false;
        }

        if (delay == 0 && gameMenuCallbacks != null && gameMenuCallbacks.isMenuOpen()) {
            return false;
        }

        new Thread() {
            public void run() {
                if (clipboardSyncRunning) {
                    return;
                }

                clipboardSyncRunning = true;
                try {
                    if (delay > 0) {
                        sleep(delay);
                    }
                    String clipboardContent = httpConn.getClipboard();
                    ClipData clipData = ClipData.newPlainText(CLIPBOARD_IDENTIFIER, clipboardContent);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        ClipDescription clipDescription = clipData.getDescription();
                        PersistableBundle newExtras = new PersistableBundle();
                        newExtras.putBoolean(CLIPBOARD_IDENTIFIER, true);
                        if (prefConfig.hideClipboardContent) {
                            // We don't know if the message is sensitive or not, to be safe mark them all as sensitive.
                            newExtras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                        }
                        clipDescription.setExtras(newExtras);
                    }

                    clipboardManager.setPrimaryClip(clipData);
                    if (prefConfig.smartClipboardSyncToast) {
                        Game.this.runOnUiThread(() -> Toast.makeText(Game.this, getString(R.string.get_clipboard_success), Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    if (prefConfig.smartClipboardSyncToast) {
                        Game.this.runOnUiThread(() -> Toast.makeText(Game.this, getString(R.string.get_clipboard_failed) + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }
                clipboardSyncRunning = false;
            }
        }.start();

        return true;
    }

    private TouchContext getTouchContext(int actionIndex, TouchContext[] inputContextMap)
    {
        if (actionIndex < inputContextMap.length) {
            return inputContextMap[actionIndex];
        }
        else {
            return null;
        }
    }

    @Override
    public void toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay");
        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput(0, 0);
    }

    private byte getLiTouchTypeFromEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                return MoonBridge.LI_TOUCH_EVENT_DOWN;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if ((event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    return MoonBridge.LI_TOUCH_EVENT_CANCEL;
                }
                else {
                    return MoonBridge.LI_TOUCH_EVENT_UP;
                }

            case MotionEvent.ACTION_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_MOVE;

            case MotionEvent.ACTION_CANCEL:
                // ACTION_CANCEL applies to *all* pointers in the gesture, so it maps to CANCEL_ALL
                // rather than CANCEL. For a single pointer cancellation, that's indicated via
                // FLAG_CANCELED on a ACTION_POINTER_UP.
                // https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi
                return MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL;

            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_HOVER;

            case MotionEvent.ACTION_HOVER_EXIT:
                return MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY;

            default:
                return -1;
        }
    }

    //灵敏度保存到集合 适配多个手指
    private Map<String,SensitivityBean> sensitivityMap=new HashMap<>();

    //修改移动的触控灵敏度（通过修改移动的距离实现） 默认使用右半边屏幕的时候开启
    private float[] getStreamViewRelativeSensitivityXY(MotionEvent event,float normalizedX,float normalizedY,int pointerIndex){
        float[] normalized=new float[2];
        normalized[0]=normalizedX;
        normalized[1]=normalizedY;

        //如果不是全局模式 并且 坐标 不在右边 则返回
        if(!prefConfig.touchSensitivityGlobal&&normalizedX<getResources().getDisplayMetrics().widthPixels/2){
            return normalized;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            SensitivityBean bean=sensitivityMap.get(String.valueOf(event.getPointerId(pointerIndex)));
            if(bean==null){
                bean=new SensitivityBean();
            }
            if(bean.getLastAbsoluteX() !=-1){
                float dx=normalizedX- bean.getLastAbsoluteX();
                float dy=normalizedY- bean.getLastAbsoluteY();
                dx*=0.01f*prefConfig.touchSensitivityX;//灵敏度
                dy*=0.01f*prefConfig.touchSensitivityY;
                normalizedX= bean.getLastRelativelyX() +dx;
                normalizedY= bean.getLastRelativelyY() +dy;
            }
            if(prefConfig.touchSensitivityRotationAuto){
                if(normalizedX>=streamView.getWidth()){
                    normalizedX=streamView.getWidth()/2.0f;
                }
                if(normalizedY>=streamView.getHeight()){
                    normalizedY=streamView.getHeight()/2.0f;
                }
            }
            bean.setLastAbsoluteX(event.getX(pointerIndex));
            bean.setLastAbsoluteY(event.getY(pointerIndex));
            bean.setLastRelativelyX(normalizedX);
            bean.setLastRelativelyY(normalizedY);
            sensitivityMap.put(String.valueOf(event.getPointerId(pointerIndex)),bean);
        }
        //抬起的时候，恢复初始化状态
        if (event.getActionMasked() == MotionEvent.ACTION_UP||event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            sensitivityMap.remove(String.valueOf(event.getPointerId(pointerIndex)));
        }
        normalized[0]=normalizedX;
        normalized[1]=normalizedY;
        return normalized;
    }


    private float[] getStreamViewRelativeNormalizedXY(View view, MotionEvent event, int pointerIndex) {
        float normalizedX = event.getX(pointerIndex);
        float normalizedY = event.getY(pointerIndex);
        //开启自定义修改触控灵敏度 并且 数值不为100
        if(prefConfig.enableTouchSensitivity&&(prefConfig.touchSensitivityX !=100||prefConfig.touchSensitivityY!=100)){
            float[] normalized=getStreamViewRelativeSensitivityXY(event,normalizedX,normalizedY,pointerIndex);
            normalizedX=normalized[0];
            normalizedY=normalized[1];
        }
        // For the containing background view, we must subtract the origin
        // of the StreamView to get video-relative coordinates.
        if (view != streamView) {
            float[] normalized = getNormalizedCoordinates(streamView, normalizedX, normalizedY);
            normalizedX = normalized[0];
            normalizedY = normalized[1];
        }

        normalizedX = Math.max(normalizedX, 0.0f);
        normalizedY = Math.max(normalizedY, 0.0f);

        normalizedX = Math.min(normalizedX, streamView.getWidth());
        normalizedY = Math.min(normalizedY, streamView.getHeight());

        normalizedX /= streamView.getWidth();
        normalizedY /= streamView.getHeight();

        return new float[] { normalizedX, normalizedY };
    }

    private float[] getNormalizedCoordinates(View streamView, float rawX, float rawY) {
        float scaleX = streamView.getScaleX();
        float scaleY = streamView.getScaleY();

        float normalizedX = (rawX - streamView.getX()) / scaleX;
        float normalizedY = (rawY - streamView.getY()) / scaleY;

        return new float[] { normalizedX, normalizedY };
    }

    private static float normalizeValueInRange(float value, InputDevice.MotionRange range) {
        return (value - range.getMin()) / range.getRange();
    }

    private static float getPressureOrDistance(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                // Hover events report distance
                if (dev != null) {
                    InputDevice.MotionRange distanceRange = dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.getSource());
                    if (distanceRange != null) {
                        return normalizeValueInRange(event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex), distanceRange);
                    }
                }
                return 0.0f;

            default:
                // Other events report pressure
                return event.getPressure(pointerIndex);
        }
    }

    private static short getRotationDegrees(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) != null) {
                short rotationDegrees = (short) Math.toDegrees(event.getOrientation(pointerIndex));
                if (rotationDegrees < 0) {
                    rotationDegrees += 360;
                }
                return rotationDegrees;
            }
        }
        return MoonBridge.LI_ROT_UNKNOWN;
    }

    private static float[] polarToCartesian(float r, float theta) {
        return new float[] { (float)(r * Math.cos(theta)), (float)(r * Math.sin(theta)) };
    }

    private static float cartesianToR(float[] point) {
        return (float)Math.sqrt(Math.pow(point[0], 2) + Math.pow(point[1], 2));
    }

    private float[] getStreamViewNormalizedContactArea(MotionEvent event, int pointerIndex) {
        float orientation;

        // If the orientation is unknown, we'll just assume it's at a 45 degree angle and scale it by
        // X and Y scaling factors evenly.
        if (event.getDevice() == null || event.getDevice().getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) == null) {
            orientation = (float)(Math.PI / 4);
        }
        else {
            orientation = event.getOrientation(pointerIndex);
        }

        float contactAreaMajor, contactAreaMinor;
        switch (event.getActionMasked()) {
            // Hover events report the tool size
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                contactAreaMajor = event.getToolMajor(pointerIndex);
                contactAreaMinor = event.getToolMinor(pointerIndex);
                break;

            // Other events report contact area
            default:
                contactAreaMajor = event.getTouchMajor(pointerIndex);
                contactAreaMinor = event.getTouchMinor(pointerIndex);
                break;
        }

        // The contact area major axis is parallel to the orientation, so we simply convert
        // polar to cartesian coordinates using the orientation as theta.
        float[] contactAreaMajorCartesian = polarToCartesian(contactAreaMajor, orientation);

        // The contact area minor axis is perpendicular to the contact area major axis (and thus
        // the orientation), so rotate the orientation angle by 90 degrees.
        float[] contactAreaMinorCartesian = polarToCartesian(contactAreaMinor, (float)(orientation + (Math.PI / 2)));

        // Normalize the contact area to the stream view size
        contactAreaMajorCartesian[0] = Math.min(Math.abs(contactAreaMajorCartesian[0]), streamView.getWidth()) / streamView.getWidth();
        contactAreaMinorCartesian[0] = Math.min(Math.abs(contactAreaMinorCartesian[0]), streamView.getWidth()) / streamView.getWidth();
        contactAreaMajorCartesian[1] = Math.min(Math.abs(contactAreaMajorCartesian[1]), streamView.getHeight()) / streamView.getHeight();
        contactAreaMinorCartesian[1] = Math.min(Math.abs(contactAreaMinorCartesian[1]), streamView.getHeight()) / streamView.getHeight();

        // Convert the normalized values back into polar coordinates
        return new float[] { cartesianToR(contactAreaMajorCartesian), cartesianToR(contactAreaMinorCartesian) };
    }

    private boolean sendPenEventForPointer(View view, MotionEvent event, byte eventType, byte toolType, int pointerIndex) {
        byte penButtons = 0;
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_PRIMARY;
        }
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_SECONDARY;
        }

        byte tiltDegrees = MoonBridge.LI_TILT_UNKNOWN;
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_TILT, event.getSource()) != null) {
                tiltDegrees = (byte)Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex));
            }
        }

        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return conn.sendPenEvent(eventType, toolType, penButtons,
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex), tiltDegrees) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private static byte convertToolTypeToStylusToolType(MotionEvent event, int pointerIndex) {
        switch (event.getToolType(pointerIndex)) {
            case MotionEvent.TOOL_TYPE_ERASER:
                return MoonBridge.LI_TOOL_TYPE_ERASER;
            case MotionEvent.TOOL_TYPE_STYLUS:
                return MoonBridge.LI_TOOL_TYPE_PEN;
            default:
                return MoonBridge.LI_TOOL_TYPE_UNKNOWN;
        }
    }

    private boolean trySendPenEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            boolean handledStylusEvent = false;
            for (int i = 0; i < event.getPointerCount(); i++) {
                byte toolType = convertToolTypeToStylusToolType(event, i);
                if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                    // Not a stylus pointer, so skip it
                    continue;
                }
                else {
                    // This pointer is a stylus, so we'll report that we handled this event
                    handledStylusEvent = true;
                }

                if (!sendPenEventForPointer(view, event, eventType, toolType, i)) {
                    // Pen events aren't supported by the host
                    return false;
                }
            }
            return handledStylusEvent;
        }
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendPenEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, MoonBridge.LI_TOOL_TYPE_UNKNOWN, (byte)0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN, MoonBridge.LI_TILT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        }
        else {
            // Up, Down, and Hover events are specific to the action index
            byte toolType = convertToolTypeToStylusToolType(event, event.getActionIndex());
            if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                // Not a stylus event
                return false;
            }
            return sendPenEventForPointer(view, event, eventType, toolType, event.getActionIndex());
        }
    }

    private boolean sendTouchEventForPointer(View view, MotionEvent event, byte eventType, int pointerIndex) {
        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return conn.sendTouchEvent(eventType, event.getPointerId(pointerIndex),
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex)) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private boolean trySendTouchEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (!sendTouchEventForPointer(view, event, eventType, i)) {
                    return false;
                }
            }
            return true;
        }
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        }
        else {
            // Up, Down, and Hover events are specific to the action index
            return sendTouchEventForPointer(view, event, eventType, event.getActionIndex());
        }
    }

    // Returns true if the event was consumed
    // NB: View is only present if called from a view callback
    private boolean handleMotionEvent(View view, MotionEvent event) {

        // Pass through mouse/touch/joystick input if we're not grabbing
        if (!grabbedInput) {
            return false;
        }

        int deviceId = event.getDeviceId();
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false;
        }

        int eventSource = event.getSource();
        int deviceSources = event.getDevice() != null ? event.getDevice().getSources() : 0;
        if ((eventSource & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            if (controllerHandler.handleMotionEvent(event)) {
                return true;
            }
        }
        else if ((deviceSources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && controllerHandler.tryHandleTouchpadEvent(event)) {
            return true;
        }
        else if ((eventSource & InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE)
        {
            boolean hasActionButton = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || (event.getActionButton() != 0);
            // This case is for mice and non-finger touch devices
            if (
                    eventSource == InputDevice.SOURCE_MOUSE ||
                            ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 && hasActionButton) || // SOURCE_TOUCHPAD
                            (eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                                    (event.getPointerCount() >= 1 &&
                                            (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
                                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)) ||
                                    eventSource == 12290) // 12290 = Samsung DeX mode desktop mouse
            ) {
                if (prefConfig.shouldDisableControl) {
                    return false;
                }
                int buttonState = event.getButtonState();
                int changedButtons = buttonState ^ lastButtonState;

                // Two finger click
                if ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 &&
                        event.getPointerCount() == 2 &&
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && event.getActionButton() == MotionEvent.BUTTON_PRIMARY)) {
                    if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) {
                        buttonState |= MotionEvent.BUTTON_SECONDARY;
                    }
                    else if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE) {
                        buttonState &= ~MotionEvent.BUTTON_SECONDARY;
                    }
                    // We may not pressing the primary button down from a previous event,
                    // so be sure to clear that bit out the button state.
                    buttonState &= ~MotionEvent.BUTTON_PRIMARY;
                    buttonState |= (lastButtonState & MotionEvent.BUTTON_PRIMARY);

                    changedButtons = buttonState ^ lastButtonState;
                }

                // Ignore mouse input if we're not capturing from our input source
                if (!inputCaptureProvider.isCapturingActive()) {
                    // We return true here because otherwise the events may end up causing
                    // Android to synthesize d-pad events.
                    return true;
                }

                // Always update the position before sending any button events. If we're
                // dealing with a stylus without hover support, our position might be
                // significantly different than before.
                if (inputCaptureProvider.eventHasRelativeMouseAxes(event)) {
                    // Send the deltas straight from the motion event
                    short deltaX = (short)inputCaptureProvider.getRelativeAxisX(event);
                    short deltaY = (short)inputCaptureProvider.getRelativeAxisY(event);

                    if (deltaX != 0 || deltaY != 0) {
                        if (prefConfig.absoluteMouseMode) {
                            // NB: view may be null, but we can unconditionally use streamView because we don't need to adjust
                            // relative axis deltas for the position of the streamView within the parent's coordinate system.
                            conn.sendMouseMoveAsMousePosition(deltaX, deltaY, (short)streamView.getWidth(), (short)streamView.getHeight());
                        }
                        else {
                            conn.sendMouseMove(deltaX, deltaY);
                        }
                    }
                }
                else if ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0) {
                    // If this input device is not associated with the view itself (like a trackpad),
                    // we'll convert the device-specific coordinates to use to send the cursor position.
                    // This really isn't ideal but it's probably better than nothing.
                    //
                    // Trackpad on newer versions of Android (Oreo and later) should be caught by the
                    // relative axes case above. If we get here, we're on an older version that doesn't
                    // support pointer capture.
                    InputDevice device = event.getDevice();
                    if (device != null) {
                        InputDevice.MotionRange xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource);
                        InputDevice.MotionRange yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource);

                        // All touchpads coordinate planes should start at (0, 0)
                        if (xRange != null && yRange != null && xRange.getMin() == 0 && yRange.getMin() == 0) {
                            int xMax = (int)xRange.getMax();
                            int yMax = (int)yRange.getMax();

                            // Touchpads must be smaller than (65535, 65535)
                            if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE) {
                                conn.sendMousePosition((short)event.getX(), (short)event.getY(),
                                        (short)xMax, (short)yMax);
                            }
                        }
                    }
                }
                else if (view != null && trySendPenEvent(view, event)) {
                    // If our host supports pen events, send it directly
                    return true;
                }
                else if (view != null) {
                    if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                        // Handle trackpad when pointer is not captured by synthesizing a trackpad movement
                        int eventAction = event.getActionMasked();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) {
                            if (!pointerSwiping) {
                                pointerSwiping = true;
                                handleTouchInput(view, event, trackpadContextMap, false, MotionEvent.ACTION_POINTER_DOWN, 1, 2);
                            }
                            return handleTouchInput(view, event, trackpadContextMap, false, MotionEvent.ACTION_MOVE, 1, 2);
                        } else if (pointerSwiping && eventAction == MotionEvent.ACTION_UP) {
                            pointerSwiping = false;
                            synthClickPending = false;
                            handleTouchInput(view, event, trackpadContextMap, false, MotionEvent.ACTION_POINTER_UP, 1, 2);
                            return true;
                        }

                        // Press & Hold / Double-Tap & Hold for Selection or Drag & Drop
                        double positionDelta = Math.sqrt(
                                Math.pow(event.getX() - lastTouchDownX, 2) +
                                Math.pow(event.getY() - lastTouchDownY, 2)
                        );

                        if (synthClickPending &&
                            event.getEventTime() - synthTouchDownTime >= prefConfig.trackpadDragDropThreshold) {
                            if (positionDelta > 50) {
                                pendingDrag = false;
                            } else if (pendingDrag) {
                                pendingDrag = false;
                                isDragging = true;
                                if (prefConfig.trackpadDragDropVibration) {
                                    Vibrator vibrator = ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE));
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        vibrator.vibrate(VibrationEffect.createOneShot(20, 127));
                                    } else {
                                        vibrator.vibrate(20);
                                    }
                                }
                                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                                return true;
                            }
                        }

                        switch (eventAction) {
                            case MotionEvent.ACTION_HOVER_MOVE:
                            case MotionEvent.ACTION_MOVE:
                                updateMousePosition(view, event);
                                return true;
                            case MotionEvent.ACTION_HOVER_EXIT:
                            case MotionEvent.ACTION_DOWN:
                                pendingDrag = true;
                                synthClickPending = true;
                                lastTouchDownX = event.getX();
                                lastTouchDownY = event.getY();
                                synthTouchDownTime = event.getEventTime();
                                return true;
                            case MotionEvent.ACTION_HOVER_ENTER:
                            case MotionEvent.ACTION_UP:
                                if (synthClickPending) {
                                    long timeDiff = event.getEventTime() - synthTouchDownTime;

                                    if (eventSource == 12290) {
                                        // Special handle for DeX
                                        // DeX reports button secondary when tapping with two fingers
                                        // So there's no need to distinguish left/right click by time difference
                                        if (timeDiff < 120) {
                                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                                        }
                                    } else {
                                        if (timeDiff < 20) {
                                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                                        } else if (timeDiff < 120) {
                                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                                        }
                                    }
                                    if (isDragging) {
                                        isDragging = false;
                                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                                    }
                                    pendingDrag = false;
                                    synthClickPending = false;
                                }
                                return true;
                            case MotionEvent.ACTION_BUTTON_PRESS:
                            case MotionEvent.ACTION_BUTTON_RELEASE:
                                synthClickPending = false;
                            default:
                                break;
                        }
                    } else {
                        updateMousePosition(view, event);
                    }
                }

                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    // Send the vertical scroll packet
                    conn.sendMouseHighResScroll((short)(event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120 * prefConfig.scrollFactor2));
                    conn.sendMouseHighResHScroll((short)(event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120 * prefConfig.scrollFactor2));
                }

                if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                }

                // Mouse secondary or stylus primary is right click (stylus down is left click)
                if ((changedButtons & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                    }
                }

                // Mouse tertiary or stylus secondary is middle click
                if ((changedButtons & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                }

                if (prefConfig.mouseNavButtons) {
                    if ((changedButtons & MotionEvent.BUTTON_BACK) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_BACK) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1);
                        }
                        else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
                        }
                    }

                    if ((changedButtons & MotionEvent.BUTTON_FORWARD) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_FORWARD) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2);
                        }
                        else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2);
                        }
                    }
                }

                // Handle stylus presses
                if (event.getPointerCount() == 1 && event.getActionIndex() == 0) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);

                            // Stylus is left click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);

                            // Eraser is right click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    }
                    else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);

                            // Stylus is left click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);

                            // Eraser is right click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    }
                }

                lastButtonState = buttonState;
            }
            // This case is for fingers
            else {
                if (eventSource == InputDevice.SOURCE_TOUCHPAD) {
                    return handleTouchInput(view, event, trackpadContextMap, false);
                } else {
                    // Log.i("Game", "fingerEvent " + event.toString());
                if (virtualController != null &&
                            (virtualController.getControllerMode() == VirtualController.ControllerMode.MoveButtons ||
                                    virtualController.getControllerMode() == VirtualController.ControllerMode.ResizeButtons)) {
                        // Ignore presses when the virtual controller is being configured
                        return true;
                    }

                    if (isPanZoomMode) {
                        // panning the streamView
                        panZoomHandler.handleTouchEvent(event);
                        return true;
                    }

                    // If touch is disabled or not initialized, we'll try panning the streamView
                    if (touchContextMap[0] == null) {
                        return true;
                    }

                    if (prefConfig.enableMultiTouchGestures || !prefConfig.enableMultiTouchScreen) {
                        int pointerCount = event.getPointerCount();
                        if (pointerCount > 2) {
                            int eventAction = event.getActionMasked();
                            if (
                                    (
                                            eventAction == MotionEvent.ACTION_POINTER_DOWN
                                                    || eventAction == MotionEvent.ACTION_POINTER_UP
                                                    || eventAction == MotionEvent.ACTION_UP
                                    )
                                            && handleMultiTouchGesture(event, eventAction, pointerCount, view)
                            ) {
                                return true;
                            }
                        }
                    }

                    if (prefConfig.enableMultiTouchScreen && !prefConfig.touchscreenTrackpad && trySendTouchEvent(view, event)) {
                        // If this host supports touch events and absolute touch is enabled,
                        // send it directly as a touch event.
                        return true;
                    }

                    return handleTouchInput(view, event, touchContextMap, true);
                }
            }

            // Handled a known source
            return true;
        }

        // Unknown class
        return false;
    }

    private boolean handleTouchInput(View view, MotionEvent event, TouchContext[] inputContextMap, boolean isTouchScreen) {
        return handleTouchInput(view, event, inputContextMap, isTouchScreen, event.getActionMasked(), event.getActionIndex(), event.getPointerCount());
    }

    private boolean handleTouchInput(View view, MotionEvent event, TouchContext[] inputContextMap, boolean isTouchScreen, int eventAction, int actionIndex, int pointerCount) {
        // Log.i("Game", "fingerEvent " + event.toString());
        int actualActionIndex = event.getActionIndex();
        int actualPointerCount = event.getPointerCount();

        boolean shouldDuplicateMovement = actualPointerCount < pointerCount;

        int origEventX = (int)event.getX(actualActionIndex);
        int origEventY = (int)event.getY(actualActionIndex);

        int eventX = origEventX;
        int eventY = origEventY;

        // Handle view scaling
        if (isTouchScreen) {
            float[] normalizedCoords = getNormalizedCoordinates(streamView, eventX, eventY);
            eventX = (int)normalizedCoords[0];
            eventY = (int)normalizedCoords[1];
        }

        // Handle view scaling - shunf4 mod logic
        int xRelScreenViewInt_shunf4ModLogic;
        int yRelScreenViewInt_shunf4ModLogic;
        {
            float xRelScreenView = origEventX;
            float yRelScreenView = origEventY;

        
            if (view != streamView) {
                xRelScreenView -= streamView.getX() + streamView.getPivotX();
                yRelScreenView -= streamView.getY() + streamView.getPivotY();
                xRelScreenView = xRelScreenView / savedScale;
                yRelScreenView = yRelScreenView / savedScale;
                xRelScreenView += streamView.getPivotX();
                yRelScreenView += streamView.getPivotY();
//                    Log.i("Game", "view != streamView");
            } else {
//                    Log.i("Game", "view == streamView");
            }

            int xRelScreenViewInt = (int)(xRelScreenView);
            int yRelScreenViewInt = (int)(yRelScreenView);

//                xRelScreenView += savedTranslateX;
//                yRelScreenView += savedTranslateY;

            xRelScreenViewInt_shunf4ModLogic = xRelScreenViewInt;
            yRelScreenViewInt_shunf4ModLogic = yRelScreenViewInt;

        }

        // Special handling for 3 finger gesture - shunf4 mod logic
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            if (event.getPointerCount() == 3) {
                // Three fingers down
                threeFingerDownTime = event.getEventTime();
//                        Log.i("Game", "ACTION_POINTER_DOWN " + event.getX(0) +" " + event.getX(1)+" " + event.getX(2));
                threeFingerDownAvgX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3;
                threeFingerDownAvgY = (event.getY(0) + event.getY(1) + event.getY(2)) / 3;
                threeFingerUpAvgX = Float.NaN;

                // Cancel the first and second touches to avoid
                // erroneous events
                for (TouchContext aTouchContext : touchContextMap) {
                    aTouchContext.cancelTouch();
                }

                return true;
            } else if (event.getPointerCount() == 4) {
                threeFingerDownTime = 0L;
                threeFingerDownAvgX = Float.NaN;
                threeFingerUpAvgX = Float.NaN;
            } else {
                threeFingerDownAvgX = Float.NaN;
                threeFingerUpAvgX = Float.NaN;
            }
        }

        TouchContext context = getTouchContext(actionIndex, inputContextMap);
        

        switch (eventAction)
        {
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_DOWN:
                if (context == null) {
                    return false;
                }

                for (TouchContext touchContext : inputContextMap) {
                    touchContext.setPointerCount(pointerCount);
                }
                context.touchDownEvent(origEventX, origEventY, xRelScreenViewInt_shunf4ModLogic, yRelScreenViewInt_shunf4ModLogic, event.getEventTime(), true);
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                //是触控板模式 三点呼出软键盘
                // shunf4 mod: allow three-finger (and up) gestures in every modes
                if(true || prefConfig.touchscreenTrackpad){
//                    Log.i("Game", "ACTION_POINTER_UP " + event.getPointerCount() + " " + event.getHistorySize() + " " + event.getActionIndex());
                    if (event.getPointerCount() == 3 && !Float.isNaN(threeFingerDownAvgX) && Float.isNaN(threeFingerUpAvgX)) {
//                        Log.i("Game", "ACTION_POINTER_UP " + event.getX(0) +" " + event.getX(1)+" " + event.getX(2));

                        threeFingerUpAvgX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3;
                        threeFingerUpAvgY = (event.getY(0) + event.getY(1) + event.getY(2)) / 3;
                    }
                    if (pointerCount == 1 &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event.getFlags() & MotionEvent.FLAG_CANCELED) == 0)) {
                        // All fingers up

                        
                        if (!Float.isNaN(threeFingerDownAvgX)) {
                            // float threeFingerDistance = (float)(Math.sqrt(Math.pow(threeFingerUpAvgX - threeFingerDownAvgX, 2) + Math.pow(threeFingerUpAvgY - threeFingerDownAvgY, 2)));
                            if (threeFingerUpAvgY - threeFingerDownAvgY <= -170.0f) {
                                toggleKeyboard();
                            } else if (threeFingerUpAvgY - threeFingerDownAvgY > 170.0f) {
                                prefConfig.enableMultiTouchScreen = false;
                                prefConfig.touchscreenTrackpad = !prefConfig.touchscreenTrackpad;
                                if (event.getEventTime() - lastThreeDownSwipe < 1300) {
                                    lastThreeDownSwipe = 0;
                                    if (prefConfig.touchscreenTrackpad) {
                                        prefConfig.shouldDoubleClickDragTranslate = !prefConfig.shouldDoubleClickDragTranslate;
                                        Toast.makeText(this, "Switched to " + (prefConfig.shouldDoubleClickDragTranslate ? "shouldDoubleClickDragTranslate" : "not-shouldDoubleClickDragTranslate"), Toast.LENGTH_SHORT).show();
                                    } else {
                                        prefConfig.modeLongPressNeededToDrag = !prefConfig.modeLongPressNeededToDrag;
                                        Toast.makeText(this, "Switched to " + (prefConfig.modeLongPressNeededToDrag ? "longPressNeededToDrag" : "not-longPressNeededToDrag"), Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    lastThreeDownSwipe = event.getEventTime();
                                    Toast.makeText(this, "Switched to " + (prefConfig.touchscreenTrackpad ? "trackpad" : "direct mouse control"), Toast.LENGTH_SHORT).show();
                                }
                                applyMouseMode(99);
                                lastThreeLeftRightSwipe = 0;
                            } else if (threeFingerUpAvgX - threeFingerDownAvgX < -170.0f) {
                                conn.sendMousePosition((short) (streamView.getWidth() / 2), (short) (streamView.getHeight() / 2), (short) streamView.getWidth(), (short) streamView.getHeight());
                                Toast.makeText(this, "Reset mouse position", Toast.LENGTH_SHORT).show();
                                if (event.getEventTime() - lastThreeLeftRightSwipe < 1300) {
                                    lastThreeLeftRightSwipe = 0;
                                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
//                                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
                                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                                    if (prefConfig.isCurrDeviceLikeOnyx) {
                                        getWindow().getDecorView().getRootView().setSystemUiVisibility(
                                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                                        //                                                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        );
                                        getWindow().getDecorView().setSystemUiVisibility(
                                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                                        //                                                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        );
                                    } else {
                                        getWindow().getDecorView().getRootView().setSystemUiVisibility(
                                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        );
                                        getWindow().getDecorView().setSystemUiVisibility(
                                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        );
                                    }

                                } else {
                                    lastThreeLeftRightSwipe = event.getEventTime();
                                }
                                lastThreeDownSwipe = 0;
                            } else if (threeFingerUpAvgX - threeFingerDownAvgX > 170.0f) {
                                conn.sendMousePosition((short) (streamView.getWidth() / 2), (short) (streamView.getHeight() / 2), (short) streamView.getWidth(), (short) streamView.getHeight());
                                Toast.makeText(this, "Reset mouse position", Toast.LENGTH_SHORT).show();
                                if (event.getEventTime() - lastThreeLeftRightSwipe < 1300) {
                                    lastThreeLeftRightSwipe = 0;
                                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
//                                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
                                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                                    if (prefConfig.isCurrDeviceLikeOnyx) {
                                        getWindow().getDecorView().getRootView().setSystemUiVisibility(
                                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
//                                                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        );
                                        getWindow().getDecorView().setSystemUiVisibility(
                                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
//                                                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        );
                                    } else {
                                        getWindow().getDecorView().getRootView().setSystemUiVisibility(
                                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        );
                                        getWindow().getDecorView().setSystemUiVisibility(
                                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        );
                                    }
                                } else {
                                    lastThreeLeftRightSwipe = event.getEventTime();
                                }
                                lastThreeDownSwipe = 0;
                            }
                            threeFingerDownAvgX = Float.NaN;
                            return true;
                        }

                        long currentEventTime = event.getEventTime();
                        if (currentEventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                            // This is a 3 finger tap to bring up the keyboard
                            toggleKeyboard();
                            return true;
                        } else if (fourFingerDownTime > 0 && currentEventTime - fourFingerDownTime < FOUR_FINGER_TAP_THRESHOLD) {
                            showHidekeyBoardLayoutController();
                            return true;
                        } else if (fourFingerDownTime > 0 && currentEventTime - fourFingerDownTime >= FOUR_FINGER_LONG_PRESS_THRESHOLD) {
                            resetTranslationAndScale();
                            return true;
                        } else if (currentEventTime - fiveFingerDownTime < FIVE_FINGER_TAP_THRESHOLD) {
                            if(prefConfig.enableBackMenu) {
                                showGameMenu(null);
                            }
                            return true;
                        }
                    }
                }
                if (context == null) {
                    return false;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    context.cancelTouch();
                }
                else {
                    context.touchUpEvent(origEventX, origEventY, xRelScreenViewInt_shunf4ModLogic, yRelScreenViewInt_shunf4ModLogic, event.getEventTime());
                }

                for (TouchContext touchContext : inputContextMap) {
                    touchContext.setPointerCount(pointerCount - 1);
                }
                if (actionIndex == 0 && pointerCount > 1 && !context.isCancelled()) {
                    // The original secondary touch now becomes primary
                    int pointer1X = (int)event.getX(1);
                    int pointer1Y = (int)event.getY(1);
                    if (isTouchScreen) {
                        float[] normalizedCoords = getNormalizedCoordinates(streamView, pointer1X, pointer1Y);
                        pointer1X = (int)normalizedCoords[0];
                        pointer1Y = (int)normalizedCoords[1];
                    }

                    int xRelScreenViewT1Int_shunf4ModLogic;
                    int yRelScreenViewT1Int_shunf4ModLogic;
                    int eventXT1Origin_shunf4ModLogic;
                    int eventYT1Origin_shunf4ModLogic;
                    {
                        float xRelScreenViewT1 = event.getX(1);
                        float yRelScreenViewT1 = event.getY(1);

                        int eventXT1 = (int)(xRelScreenViewT1);
                        int eventYT1 = (int)(yRelScreenViewT1);

                        if (view != streamView) {
                            xRelScreenViewT1 -= streamView.getX() + streamView.getPivotX();
                            yRelScreenViewT1 -= streamView.getY() + streamView.getPivotY();
                            xRelScreenViewT1 = xRelScreenViewT1 / savedScale;
                            yRelScreenViewT1 = yRelScreenViewT1 / savedScale;
                            xRelScreenViewT1 += streamView.getPivotX();
                            yRelScreenViewT1 += streamView.getPivotY();
//                            Log.i("Game", "view != streamView");
                        } else {
                        }

                        int xRelScreenViewT1Int = (int)(xRelScreenViewT1);
                        int yRelScreenViewT1Int = (int)(yRelScreenViewT1);

                        xRelScreenViewT1Int_shunf4ModLogic = xRelScreenViewT1Int;
                        yRelScreenViewT1Int_shunf4ModLogic = yRelScreenViewT1Int;
                        eventXT1Origin_shunf4ModLogic = eventXT1;
                        eventYT1Origin_shunf4ModLogic = eventYT1;
                    }

                    // context.touchDownEvent(
                    //         pointer1X,
                    //         pointer1Y,
                    //         event.getEventTime(), false);
                    context.touchDownEvent(
                                eventXT1Origin_shunf4ModLogic,
                                eventYT1Origin_shunf4ModLogic,
                                xRelScreenViewT1Int_shunf4ModLogic,
                                yRelScreenViewT1Int_shunf4ModLogic,
                                event.getEventTime(), false);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (context == null) {
                    return false;
                }
                // ACTION_MOVE is special because it always has actionIndex == 0
                // We'll call the move handlers for all indexes manually

                // First process the historical events
                for (int i = 0; i < event.getHistorySize(); i++) {
                    for (TouchContext aTouchContextMap : inputContextMap) {
                        if (aTouchContextMap.getActionIndex() < pointerCount)
                        {
                            int aActionIndex = shouldDuplicateMovement ? 0 : aTouchContextMap.getActionIndex();
                            int historicalX = (int)event.getHistoricalX(aActionIndex, i);
                            int historicalY = (int)event.getHistoricalY(aActionIndex, i);
                            if (isTouchScreen) {
                                float[] normalizedCoords = getNormalizedCoordinates(streamView, historicalX, historicalY);
                                historicalX = (int)normalizedCoords[0];
                                historicalY = (int)normalizedCoords[1];
                            }

                            int xRelScreenViewCurrInt_shunf4ModLogic;
                            int yRelScreenViewCurrInt_shunf4ModLogic;
                            int eventXCurrOrigin_shunf4ModLogic;
                            int eventYCurrOrigin_shunf4ModLogic;
                            {
                                float xRelScreenViewCurr = event.getHistoricalX(aTouchContextMap.getActionIndex(), i);
                                float yRelScreenViewCurr = event.getHistoricalY(aTouchContextMap.getActionIndex(), i);

                                int eventXCurr = (int)(xRelScreenViewCurr);
                                int eventYCurr = (int)(yRelScreenViewCurr);

                                if (view != streamView) {
                                    xRelScreenViewCurr -= streamView.getX() + streamView.getPivotX();
                                    yRelScreenViewCurr -= streamView.getY() + streamView.getPivotY();
                                    xRelScreenViewCurr = xRelScreenViewCurr / savedScale;
                                    yRelScreenViewCurr = yRelScreenViewCurr / savedScale;
                                    xRelScreenViewCurr += streamView.getPivotX();
                                    yRelScreenViewCurr += streamView.getPivotY();
                                } else {
                                }

                                int xRelScreenViewCurrInt = (int)(xRelScreenViewCurr);
                                int yRelScreenViewCurrInt = (int)(yRelScreenViewCurr);
                                xRelScreenViewCurrInt_shunf4ModLogic = xRelScreenViewCurrInt;
                                yRelScreenViewCurrInt_shunf4ModLogic = yRelScreenViewCurrInt;
                                eventXCurrOrigin_shunf4ModLogic = eventXCurr;
                                eventYCurrOrigin_shunf4ModLogic = eventYCurr;
                            }
                            
                            // aTouchContextMap.touchMoveEvent(
                            //         historicalX,
                            //         historicalY,
                            //         event.getHistoricalEventTime(i));
                            aTouchContextMap.touchMoveEvent(
                                        eventXCurrOrigin_shunf4ModLogic,
                                        eventYCurrOrigin_shunf4ModLogic,
                                        xRelScreenViewCurrInt_shunf4ModLogic,
                                        yRelScreenViewCurrInt_shunf4ModLogic,
                                        event.getHistoricalEventTime(i));
                        }
                    }
                }

                // Now process the current values
                for (TouchContext aTouchContextMap : inputContextMap) {
                    if (aTouchContextMap.getActionIndex() < pointerCount)
                    {
                        int aActionIndex = shouldDuplicateMovement ? 0 : aTouchContextMap.getActionIndex();
                        int currentX = (int)event.getX(aActionIndex);
                        int currentY = (int)event.getY(aActionIndex);
                        if (isTouchScreen) {
                            float[] normalizedCoords = getNormalizedCoordinates(streamView, currentX, currentY);
                            currentX = (int)normalizedCoords[0];
                            currentY = (int)normalizedCoords[1];
                        }

                        int eventXCurrOrigin_shunf4ModLogic;
                        int eventYCurrOrigin_shunf4ModLogic;
                        int xRelScreenViewCurrInt_shunf4ModLogic;
                        int yRelScreenViewCurrInt_shunf4ModLogic;
                        {
                            float xRelScreenViewCurr = event.getX(aTouchContextMap.getActionIndex());
                            float yRelScreenViewCurr = event.getY(aTouchContextMap.getActionIndex());

                            int eventXCurr = (int)(xRelScreenViewCurr);
                            int eventYCurr = (int)(yRelScreenViewCurr);

                            if (view != streamView) {
                                xRelScreenViewCurr -= streamView.getX() + streamView.getPivotX();
                                yRelScreenViewCurr -= streamView.getY() + streamView.getPivotY();
                                xRelScreenViewCurr = xRelScreenViewCurr / savedScale;
                                yRelScreenViewCurr = yRelScreenViewCurr / savedScale;
                                xRelScreenViewCurr += streamView.getPivotX();
                                yRelScreenViewCurr += streamView.getPivotY();
                            } else {
                            }

                            int xRelScreenViewCurrInt = (int)(xRelScreenViewCurr);
                            int yRelScreenViewCurrInt = (int)(yRelScreenViewCurr);

                            eventXCurrOrigin_shunf4ModLogic = eventXCurr;
                            eventYCurrOrigin_shunf4ModLogic = eventYCurr;
                            xRelScreenViewCurrInt_shunf4ModLogic = xRelScreenViewCurrInt;
                            yRelScreenViewCurrInt_shunf4ModLogic = yRelScreenViewCurrInt;
                        }

                        // aTouchContextMap.touchMoveEvent(
                        //         currentX,
                        //         currentY,
                        //         event.getEventTime());

                        aTouchContextMap.touchMoveEvent(
                                    eventXCurrOrigin_shunf4ModLogic,
                                    eventYCurrOrigin_shunf4ModLogic,
                                    xRelScreenViewCurrInt_shunf4ModLogic,
                                    yRelScreenViewCurrInt_shunf4ModLogic,
                                    event.getEventTime());
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (TouchContext aTouchContext : inputContextMap) {
                    aTouchContext.cancelTouch();
                    aTouchContext.setPointerCount(0);
                }
                break;
            default:
                return false;
        }

        return true;
    }

    private boolean handleMultiTouchGesture(MotionEvent event, int eventAction, int pointerCount, View view) {

        if (eventAction == MotionEvent.ACTION_POINTER_DOWN) {
            if (pointerCount == 3) {
                // shunf4 mod: to support three-finger swipe gesture above, ignore handlers here
//                threeFingerDownTime = event.getEventTime();
            } else if (pointerCount == 4) {
                threeFingerDownTime = 0;
                fourFingerDownTime = event.getEventTime();
            } else if (pointerCount == 5) {
                threeFingerDownTime = 0;
                fourFingerDownTime = 0;
                fiveFingerDownTime = event.getEventTime();
            }
        }

        switch (eventAction) {
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                long currentEventTime = event.getEventTime();
                if (pointerCount >= 5 && fiveFingerDownTime > 0 && currentEventTime - fiveFingerDownTime < FIVE_FINGER_TAP_THRESHOLD) {
                    if(prefConfig.enableBackMenu) {
                        showGameMenu(null);
                    }
                    fiveFingerDownTime = 0;
                    break;
                } else if (pointerCount == 4 && fourFingerDownTime > 0 && currentEventTime - fourFingerDownTime < FOUR_FINGER_TAP_THRESHOLD) {
                    showHidekeyBoardLayoutController();
                    fourFingerDownTime = 0;
                    break;
                } else if (pointerCount == 4 && fourFingerDownTime > 0 && currentEventTime - fourFingerDownTime >= FOUR_FINGER_LONG_PRESS_THRESHOLD) {
                    resetTranslationAndScale();
                    fourFingerDownTime = 0;
                    break;
                } else if (pointerCount == 3 && threeFingerDownTime > 0 && currentEventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                    // shunf4 mod: to support three-finger swipe gesture above, ignore handlers here
//                    toggleKeyboard();
//                    threeFingerDownTime = 0;
                    // shunf4 mod: don't break here, because we don't want it return true (handled)
//                    break;
                }
//                threeFingerDownTime = 0;
                fourFingerDownTime = 0;
                fiveFingerDownTime = 0;

                cancelStaleTouchState(event, view);
                return false;
            default:
                return false;
        }

        cancelStaleTouchState(event, view);
        return true;
    }

    private void resetTranslationAndScale() {
        streamView.setTranslationX(0.0f);
        streamView.setTranslationY(0.0f);
        streamView.setScaleX(1.0f);
        streamView.setScaleY(1.0f);
        savedScale = 1.0f;
        savedTranslateX = 0.0f;
        savedTranslateY = 0.0f;
        conn.sendMousePosition((short) (streamView.getWidth() / 2), (short) (streamView.getHeight() / 2), (short) streamView.getWidth(), (short) streamView.getHeight());
        Toast.makeText(this, "Reset transform and mouse position", Toast.LENGTH_SHORT).show();
    }

    private void cancelStaleTouchState(MotionEvent event, View view) {
        MotionEvent cancelEvent = MotionEvent.obtain(event);
        cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
        view.dispatchTouchEvent(cancelEvent);
        cancelEvent.recycle();
        for (TouchContext aTouchContext : touchContextMap) {
            aTouchContext.cancelTouch();
            aTouchContext.setPointerCount(0);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return handleMotionEvent(null, event) || super.onGenericMotionEvent(event);

    }

    private void updateMousePosition(View touchedView, MotionEvent event) {
        // X and Y are already relative to the provided view object
        float eventX, eventY;

        // For our StreamView itself, we can use the coordinates unmodified.
        if (touchedView == streamView) {
            eventX = event.getX(0);
            eventY = event.getY(0);
        }
        else {
            // For the containing background view, we must subtract the origin
            // of the StreamView to get video-relative coordinates.
            eventX = event.getX(0) - streamView.getX();
            eventY = event.getY(0) - streamView.getY();
        }

        if (event.getPointerCount() == 1 && event.getActionIndex() == 0 &&
                (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS))
        {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_EXIT:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (event.getEventTime() - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchUpX, 2) + Math.pow(eventY - lastAbsTouchUpY, 2)) <= STYLUS_UP_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch up and hover or touch down to allow more precise double-clicking
                        return;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (event.getEventTime() - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchDownX, 2) + Math.pow(eventY - lastAbsTouchDownY, 2)) <= STYLUS_DOWN_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch down and move or touch up to allow more precise double-clicking
                        return;
                    }
                    break;
            }
        }

        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = Math.min(Math.max(eventX, 0), streamView.getWidth());
        eventY = Math.min(Math.max(eventY, 0), streamView.getHeight());

        conn.sendMousePosition((short)eventX, (short)eventY, (short)streamView.getWidth(), (short)streamView.getHeight());
    }

    @Override
    public boolean onGenericMotion(View view, MotionEvent event) {
        return handleMotionEvent(view, event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // Tell the OS not to buffer input events for us
            //
            // NB: This is still needed even when we call the newer requestUnbufferedDispatch()!
            view.requestUnbufferedDispatch(event);
        }

        return handleMotionEvent(view, event);
    }

    @Override
    public void stageStarting(final String stage) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (spinner != null) {
                    spinner.setMessage(getResources().getString(R.string.conn_starting) + " " + stage);
                }
            }
        });
    }

    @Override
    public void stageComplete(String stage) {
    }

    private void stopConnection() {
        if (connecting || connected) {
            connecting = connected = false;
            updatePipAutoEnter();

            controllerHandler.stop();

            // Update GameManager state to indicate we're no longer in game
            UiHelper.notifyStreamEnded(this);

            // Stop may take a few hundred ms to do some network I/O to tell
            // the server we're going away and clean up. Let it run in a separate
            // thread to keep things smooth for the UI. Inside moonlight-common,
            // we prevent another thread from starting a connection before and
            // during the process of stopping this one.
            new Thread() {
                public void run() {
                    conn.stop();
                    if (httpConn != null && quitOnStop) {
                        try {
                            sleep(1000);
                            httpConn.quitApp();
                            Game.this.runOnUiThread(() -> Toast.makeText(Game.this, Game.this.getResources().getString(R.string.applist_quit_success) + " " + appName, Toast.LENGTH_LONG).show());
                        } catch (Exception e) {
                            Game.this.runOnUiThread(() -> Toast.makeText(Game.this, e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }
                }
            }.start();
        }
    }

    @Override
    public boolean stageFailed(final String stage, final int portFlags, final int errorCode) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        final int portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443, portFlags);

        if (errorCode == 0 && portFlags != 0 && (portTestResult == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE || portTestResult == 0)) {
            spinner.setMessage(getResources().getString(R.string.unlocking_or_starting));
            return true;
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (spinner != null) {
                    spinner.dismiss();
                    spinner = null;
                }

                if (!displayedFailureDialog) {
                    displayedFailureDialog = true;
                    LimeLog.severe(stage + " failed: " + errorCode);

                    // If video initialization failed and the surface is still valid, display extra information for the user
                    if (stage.contains("video") && streamView.getHolder().getSurface().isValid()) {
                        Toast.makeText(Game.this, getResources().getText(R.string.video_decoder_init_failed), Toast.LENGTH_LONG).show();
                    }

                    String dialogText = getResources().getString(R.string.conn_error_msg) + " " + stage +" (error "+errorCode+")";

                    switch (errorCode) {
                        case 403: {
                            dialogText += "\n\n" + getResources().getString(R.string.error_msg_permission_denied) + " (" + getResources().getString(R.string.permission_launch_app) + ")";
                            break;
                        }
                        case -408: {
                            dialogText += "\n\n" + getResources().getString(R.string.error_msg_timeout);
                            break;
                        }
                        default: {
                            // do nothing
                        }
                    }

                    if (portFlags != 0) {
                        dialogText += "\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
                                MoonBridge.stringifyPortFlags(portFlags, "\n");
                    }

                    if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0)  {
                        dialogText += "\n\n" + getResources().getString(R.string.nettest_text_blocked);
                    }

                    Dialog.displayDialog(Game.this, getResources().getString(R.string.conn_error_title), dialogText, true);
                }
            }
        });

        return false;
    }

    @Override
    public void connectionTerminated(final int errorCode) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        final int portFlags = MoonBridge.getPortFlagsFromTerminationErrorCode(errorCode);
        final int portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER,443, portFlags);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Let the display go to sleep now
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                // Stop processing controller input
                controllerHandler.stop();

                // Ungrab input
                setInputGrabState(false);

                if (!displayedFailureDialog) {
                    displayedFailureDialog = true;
                    LimeLog.severe("Connection terminated: " + errorCode);
                    stopConnection();

                    // Display the error dialog if it was an unexpected termination.
                    // Otherwise, just finish the activity immediately.
                    if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
                        String message;

                        if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                            // If we got a blocked result, that supersedes any other error message
                            message = getResources().getString(R.string.nettest_text_blocked);
                        }
                        else {
                            switch (errorCode) {
                                case MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC:
                                    message = getResources().getString(R.string.no_video_received_error);
                                    break;

                                case MoonBridge.ML_ERROR_NO_VIDEO_FRAME:
                                    message = getResources().getString(R.string.no_frame_received_error);
                                    break;

                                case MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION:
                                case MoonBridge.ML_ERROR_PROTECTED_CONTENT:
                                    message = getResources().getString(R.string.early_termination_error);
                                    break;

                                case MoonBridge.ML_ERROR_FRAME_CONVERSION:
                                    message = getResources().getString(R.string.frame_conversion_error);
                                    break;

                                default:
                                    String errorCodeString;
                                    // We'll assume large errors are hex values
                                    if (Math.abs(errorCode) > 1000) {
                                        errorCodeString = Integer.toHexString(errorCode);
                                    }
                                    else {
                                        errorCodeString = Integer.toString(errorCode);
                                    }
                                    message = getResources().getString(R.string.conn_terminated_msg) + "\n\n" +
                                            getResources().getString(R.string.error_code_prefix) + " " + errorCodeString;
                                    break;
                            }
                        }

                        if (portFlags != 0) {
                            message += "\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
                                    MoonBridge.stringifyPortFlags(portFlags, "\n");
                        }

                        Dialog.displayDialog(Game.this, getResources().getString(R.string.conn_terminated_title),
                                message, true);
                    }
                    else {
                        finish();
                    }
                }
            }
        });
    }

    @Override
    public void connectionStatusUpdate(final int connectionStatus) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (prefConfig.disableWarnings) {
                    return;
                }

                if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
                    if (prefConfig.bitrate > 5000) {
                        notificationOverlayView.setText(getResources().getString(R.string.slow_connection_msg));
                    }
                    else {
                        notificationOverlayView.setText(getResources().getString(R.string.poor_connection_msg));
                    }

                    requestedNotificationOverlayVisibility = View.VISIBLE;
                }
                else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY) {
                    requestedNotificationOverlayVisibility = View.GONE;
                }

                if (!isHidingOverlays) {
                    notificationOverlayView.setVisibility(requestedNotificationOverlayVisibility);
                }
            }
        });
    }

    @Override
    public void connectionStarted() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (spinner != null) {
                    spinner.dismiss();
                    spinner = null;
                }

                connected = true;
                connecting = false;
                updatePipAutoEnter();

                // Hide the mouse cursor now after a short delay.
                // Doing it before dismissing the spinner seems to be undone
                // when the spinner gets displayed. On Android Q, even now
                // is too early to capture. We will delay a second to allow
                // the spinner to dismiss before capturing.
                Handler h = new Handler();
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        setInputGrabState(true);
                    }
                }, 500);

                // Keep the display on
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                // Update GameManager state to indicate we're in game
                UiHelper.notifyStreamConnected(Game.this);

                // Sync local clipboard to host
                handleFocusChange(true);

                hideSystemUi(1000);
            }
        });

        if (prefConfig.usbDriver) {
            // Start the USB driver
            bindService(new Intent(this, UsbDriverService.class),
                    usbDriverServiceConnection, Service.BIND_AUTO_CREATE);
        }

        // Report this shortcut being used (off the main thread to prevent ANRs)
        ComputerDetails computer = new ComputerDetails();
        computer.name = pcName;
        computer.uuid = Game.this.getIntent().getStringExtra(EXTRA_PC_UUID);
        ShortcutHelper shortcutHelper = new ShortcutHelper(this);
        shortcutHelper.reportComputerShortcutUsed(computer);
        if (appName != null) {
            // This may be null if launched from the "Resume Session" PC context menu item
            shortcutHelper.reportGameLaunched(computer, app);
        }
    }

    @Override
    public void displayMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void displayTransientMessage(final String message) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    public void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        LimeLog.info(String.format((Locale)null, "Rumble on gamepad %d: %04x %04x", controllerNumber, lowFreqMotor, highFreqMotor));

        controllerHandler.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor);
    }

    @Override
    public void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        LimeLog.info(String.format((Locale)null, "Rumble on gamepad triggers %d: %04x %04x", controllerNumber, leftTrigger, rightTrigger));

        controllerHandler.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger);
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        LimeLog.info("Display HDR mode: " + (enabled ? "enabled" : "disabled"));
        decoderRenderer.setHdrMode(enabled, hdrMetadata);
    }

    @Override
    public void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz) {
        controllerHandler.handleSetMotionEventState(controllerNumber, motionType, reportRateHz);
    }

    @Override
    public void setControllerLED(short controllerNumber, byte r, byte g, byte b) {
        controllerHandler.handleSetControllerLED(controllerNumber, r, g, b);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!surfaceCreated) {
            throw new IllegalStateException("Surface changed before creation!");
        }

        LimeLog.info("surfaceChanged-->"+width+" x "+height + "----"+displayWidth+" x "+displayHeight);

        if (!attemptedConnection) {
            attemptedConnection = true;

            // Update GameManager state to indicate we're "loading" while connecting
            UiHelper.notifyStreamConnecting(Game.this);

            AudioRenderer audioRenderer;

            if (prefConfig.shouldDisableAudio) {
                audioRenderer = new AudioRenderer() {
                    @Override
                    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
                        return 0;
                    }

                    @Override
                    public void start() {

                    }

                    @Override
                    public void stop() {

                    }

                    @Override
                    public void playDecodedAudio(short[] audioData) {

                    }

                    @Override
                    public void cleanup() {

                    }
                };
            } else {
                audioRenderer = new AndroidAudioRenderer(Game.this, prefConfig.enableAudioFx);
            }

            if (prefConfig.shouldDisableVideo) {
                conn.start(audioRenderer,
                        new VideoDecoderRenderer() {
                            @Override
                            public int setup(int format, int width, int height, int redrawRate) {
                                 return 0;
                            }

                            @Override
                            public void start() {

                            }

                            @Override
                            public void stop() {

                            }

                            @Override
                            public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType, int frameNumber, int frameType, char frameHostProcessingLatency, long receiveTimeMs, long enqueueTimeMs) {
                                return 0;
                            }

                            @Override
                            public void cleanup() {

                            }

                            @Override
                            public int getCapabilities() {
                                return decoderRenderer.getCapabilities();
                            }

                            @Override
                            public void setHdrMode(boolean enabled, byte[] hdrMetadata) {

                            }

                            @Override
                            public void shunf4ModReload() {

                            }
                        }, Game.this);
            } else {
                decoderRenderer.setRenderTarget(holder.getSurface());
                decoderRenderer.onSurfaceSizeChanged(width, height);
                conn.start(audioRenderer,
                        decoderRenderer, Game.this);
            }
        }

        panZoomHandler.handleSurfaceChange();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isInPictureInPictureMode()) {
                updatePipAutoEnter();
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        float desiredFrameRate;

        surfaceCreated = true;

        // Android will pick the lowest matching refresh rate for a given frame rate value, so we want
        // to report the true FPS value if refresh rate reduction is enabled. We also report the true
        // FPS value if there's no suitable matching refresh rate. In that case, Android could try to
        // select a lower refresh rate that avoids uneven pull-down (ex: 30 Hz for a 60 FPS stream on
        // a display that maxes out at 50 Hz).
        if (mayReduceRefreshRate() || desiredRefreshRate < prefConfig.fps) {
            desiredFrameRate = prefConfig.fps;
        }
        else {
            // Otherwise, we will pretend that our frame rate matches the refresh rate we picked in
            // prepareDisplayForRendering(). This will usually be the highest refresh rate that our
            // frame rate evenly divides into, which ensures the lowest possible display latency.
            desiredFrameRate = desiredRefreshRate;
        }

        // Tell the OS about our frame rate to allow it to adapt the display refresh rate appropriately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // We want to change frame rate even if it's not seamless, since prepareDisplayForRendering()
            // will not set the display mode on S+ if it only differs by the refresh rate. It depends
            // on us to trigger the frame rate switch here.
            holder.getSurface().setFrameRate(desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.getSurface().setFrameRate(desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (!surfaceCreated) {
            throw new IllegalStateException("Surface destroyed before creation!");
        }

        if (attemptedConnection) {
            // Let the decoder know immediately that the surface is gone
            decoderRenderer.prepareForStop();

            if (connected) {
                stopConnection();
            }
        }
    }

    @Override
    public void mouseMove(int deltaX, int deltaY) {
        conn.sendMouseMove((short) deltaX, (short) deltaY);
    }

    @Override
    public void mouseButtonEvent(int buttonId, boolean down) {
        byte buttonIndex;

        switch (buttonId)
        {
            case EvdevListener.BUTTON_LEFT:
                buttonIndex = MouseButtonPacket.BUTTON_LEFT;
                break;
            case EvdevListener.BUTTON_MIDDLE:
                buttonIndex = MouseButtonPacket.BUTTON_MIDDLE;
                break;
            case EvdevListener.BUTTON_RIGHT:
                buttonIndex = MouseButtonPacket.BUTTON_RIGHT;
                break;
            case EvdevListener.BUTTON_X1:
                buttonIndex = MouseButtonPacket.BUTTON_X1;
                break;
            case EvdevListener.BUTTON_X2:
                buttonIndex = MouseButtonPacket.BUTTON_X2;
                break;
            default:
                LimeLog.warning("Unhandled button: "+buttonId);
                return;
        }

        if (down) {
            conn.sendMouseButtonDown(buttonIndex);
        }
        else {
            conn.sendMouseButtonUp(buttonIndex);
        }
    }

    @Override
    public void mouseVScroll(byte amount) {
        conn.sendMouseScroll(amount);
    }

    @Override
    public void mouseHScroll(byte amount) {
        conn.sendMouseHScroll(amount);
    }

    @Override
    public void keyboardEvent(boolean buttonDown, short keyCode) {
        short keyMap = keyboardTranslator.translate(keyCode, 0, -1);
        if (keyMap != 0) {
            // handleSpecialKeys() takes the Android keycode
            if (handleSpecialKeys(keyCode, buttonDown)) {
                return;
            }

            if (buttonDown) {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, getModifierState(), (byte)0);
            }
            else {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, getModifierState(), (byte)0);
            }
        }
    }

    @Override
    public void onSystemUiVisibilityChange(int visibility) {
        // Don't do anything if we're not connected
        if (!connected) {
            return;
        }

        // This flag is set for all devices
        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            hideSystemUi(2000);
        }
        else if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            if (!prefConfig.isCurrDeviceLikeOnyx) {
                hideSystemUi(2000);
            }
        }
    }

    @Override
    public void onPerfUpdate(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(prefConfig.enablePerfOverlayLite){
                    performanceOverlayLite.setText(text);
                }else{
                    performanceOverlayBig.setText(text);
                }
            }
        });
    }

    @Override
    public void onUsbPermissionPromptStarting() {
        // Disable PiP auto-enter while the USB permission prompt is on-screen. This prevents
        // us from entering PiP while the user is interacting with the OS permission dialog.
        suppressPipRefCount++;
        updatePipAutoEnter();
    }

    @Override
    public void onUsbPermissionPromptCompleted() {
        suppressPipRefCount--;
        updatePipAutoEnter();
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
        switch (keyEvent.getAction()) {
            case KeyEvent.ACTION_DOWN:
                return handleKeyDown(keyEvent);
            case KeyEvent.ACTION_UP:
                return handleKeyUp(keyEvent);
            case KeyEvent.ACTION_MULTIPLE:
                return handleKeyMultiple(keyEvent);
            default:
                return false;
        }
    }

    @Override
    public void onBackPressed() {
        if(prefConfig.enableBackMenu){
            showGameMenu(null);
            return;
        }
        super.onBackPressed();
    }

    public void sendExecServerCmd(int cmdId) {
        conn.sendExecServerCmd(cmdId);
    }

    public ArrayList<String> getServerCmds() {
        return serverCommands;
    }

    public boolean isZoomModeEnabled() {
        return isPanZoomMode;
    }
    public void toggleZoomMode() {
        this.isPanZoomMode = !this.isPanZoomMode;
    }

    public void rotateScreen() {
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentOrientation = Configuration.ORIENTATION_PORTRAIT;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
        } else {
            currentOrientation = Configuration.ORIENTATION_LANDSCAPE;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
        }
    }

    public void selectMouseMode(){
        String[] strings = getResources().getStringArray(R.array.mouse_mode_names);
        String[] items = Arrays.copyOf(strings,strings.length + 1);
        items[items.length - 1] = getString(R.string.toggle_local_mouse_cursor);
        new AlertDialog.Builder(this).setItems(items, (dialog, which) -> {
            dialog.dismiss();
            if(which == strings.length){
                toggleMouseLocalCursor();
                return;
            }
            applyMouseMode(which);
        }).setTitle(getString(R.string.game_menu_select_mouse_mode)).create().show();
    }

    //本地鼠标光标切换
    private void toggleMouseLocalCursor(){
        if (!grabbedInput) {
            inputCaptureProvider.enableCapture();
            grabbedInput = true;
        }
        cursorVisible = !cursorVisible;
        if (cursorVisible) {
            inputCaptureProvider.showCursor();
        } else {
            inputCaptureProvider.hideCursor();
        }
    }

    private void applyMouseMode(Integer mode) {
        if (mode == null) {
            if (lastMouseMode == null) {
                return;
            } else {
                mode = lastMouseMode.intValue();
            }
        }
        lastMouseMode = mode;
        switch (mode) {
            case 99: // shunf4 mod compatible logic
                break;
            case 0: // Multi-touch
            prefConfig.enableMultiTouchScreen = true;
            prefConfig.touchscreenTrackpad = false;
            break;
            case 1: // Normal mouse
            case 5: // Normal mouse with swapped buttons
                prefConfig.enableMultiTouchScreen = false;
                prefConfig.touchscreenTrackpad = false;
                break;
            case 2: // Trackpad (natural)
            case 3: // Trackpad (gaming)
                prefConfig.enableMultiTouchScreen = false;
                prefConfig.touchscreenTrackpad = true;
                break;
            case 4: // Touch mouse disabled
                break;
            default:
                break;
        }

        //Initialize touch contexts

        TouchContext.ScaleTransformCallback scaleTransformCallback = (tx, ty, s, c) -> {
            float currScale = Double.isNaN(s) ? pendingScale : (float)(savedScale * s);
            float currTranslateX = tx == -1 ? pendingTranslateX : (float)(s * savedTranslateX + tx);
            float currTranslateY = ty == -1 ? pendingTranslateY : (float)(s * savedTranslateY + ty);

            if (Float.isNaN(currScale) || Float.isNaN(currTranslateX) || Float.isNaN(currTranslateY)) {
                return;
            }

            if (c) {
                savedScale = currScale;
                savedTranslateX = currTranslateX;
                savedTranslateY = currTranslateY;
                if (Math.abs(savedTranslateX) > 3000.0f || Math.abs(savedTranslateY) > 2500.0f || savedScale > 40.0f || savedScale < 0.03f) {
                    savedScale = 1.0f;
                    savedTranslateX = 0.0f;
                    savedTranslateY = 0.0f;
                }
                currScale = savedScale;
                currTranslateX = savedTranslateX;
                currTranslateY = savedTranslateY;

                pendingScale = Float.NaN;
                pendingTranslateX = Float.NaN;
                pendingTranslateY = Float.NaN;
            } else {
                pendingScale = currScale;
                pendingTranslateX = currTranslateX;
                pendingTranslateY = currTranslateY;
            }

            streamView.setTranslationX(currTranslateX);
            streamView.setTranslationY(currTranslateY);
            streamView.setScaleX(currScale);
            streamView.setScaleY(currScale);
        };

        DisplayMetrics screen = getResources().getDisplayMetrics();

        Vibrator vibrator;
        if (Build.VERSION.SDK_INT>=31) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vibratorManager.getDefaultVibrator();
        }
        else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        for (int i = 0; i < touchContextMap.length; i++) {
            if (touchContextMap[i] != null) touchContextMap[i].cancelTouch();
            if (mode == 4) {
                // Touch mouse disabled
                touchContextMap[i] = null;
            } else if (prefConfig.shouldDisableControl) {
                touchContextMap[i] = new ScaleTranslateOnlyTouchContext(conn, i, streamView,
                        backgroundTouchView.getWidth(),
                        backgroundTouchView.getHeight(),
                        prefConfig.modeLongPressNeededToDrag,
                        prefConfig.edgeSingleFingerScrollWidth,
                        prefConfig.shouldDoubleClickDragTranslate,
                        prefConfig.absoluteTouchTapOnlyPlacesMouse,
                        vibrator,
                        (otherTouchIndex) -> {
                            TouchContext otherTouchContext = touchContextMap[otherTouchIndex];
                            return Pair.create(otherTouchContext.getLastTouchX(), otherTouchContext.getLastTouchY());
                        },
                        scaleTransformCallback,
                        () -> confirmedScaleTranslate,
                        (x) -> { confirmedScaleTranslate = x; },
                        () -> doubleFingerInitialSpacing,
                        (x) -> { doubleFingerInitialSpacing = x; },
                        () -> doubleFingerInitialMidpointX,
                        (x) -> { doubleFingerInitialMidpointX = x; },
                        () -> doubleFingerInitialMidpointY,
                        (y) -> { doubleFingerInitialMidpointY = y; }
                );
            } else if (!prefConfig.touchscreenTrackpad) {
                touchContextMap[i] = new AbsoluteTouchContext(conn, i, streamView, mode == 5,
                        backgroundTouchView.getWidth(),
                        backgroundTouchView.getHeight(),
                        prefConfig.modeLongPressNeededToDrag,
                        prefConfig.edgeSingleFingerScrollWidth,
                        prefConfig.scrollFactor2,
                        prefConfig.shouldDoubleClickDragTranslate,
                        prefConfig.absoluteTouchTapOnlyPlacesMouse,
                        vibrator,
                        (otherTouchIndex) -> {
                            TouchContext otherTouchContext = touchContextMap[otherTouchIndex];
                            return Pair.create(otherTouchContext.getLastTouchX(), otherTouchContext.getLastTouchY());
                        },
                        scaleTransformCallback,
                        () -> confirmedScaleTranslate,
                        (x) -> { confirmedScaleTranslate = x; },
                        () -> doubleFingerInitialSpacing,
                        (x) -> { doubleFingerInitialSpacing = x; },
                        () -> doubleFingerInitialMidpointX,
                        (x) -> { doubleFingerInitialMidpointX = x; },
                        () -> doubleFingerInitialMidpointY,
                        (y) -> { doubleFingerInitialMidpointY = y; }
                );
            } else if (mode == 3 || mode == 99) {
                touchContextMap[i] = new RelativeTouchContext(conn, i,
                        REFERENCE_HORIZ_RES / (prefConfig.isCurrDeviceLikeOnyx ? 2 : 1), REFERENCE_VERT_RES / (prefConfig.isCurrDeviceLikeOnyx ? 2 : 1),
                        streamView,
                        prefConfig,
                        backgroundTouchView.getWidth(),
                        backgroundTouchView.getHeight(),
                        prefConfig.edgeSingleFingerScrollWidth,
                        prefConfig.scrollFactor2,
                        prefConfig.shouldDoubleClickDragTranslate,
                        prefConfig.shouldRelativeLongPressRightClick,
                        vibrator,
                        () -> lastLeftMouseTapTime,
                        x -> { lastLeftMouseTapTime = x; },
                        (otherTouchIndex) -> {
                            TouchContext otherTouchContext = touchContextMap[otherTouchIndex];
                            return Pair.create(otherTouchContext.getLastTouchX(), otherTouchContext.getLastTouchY());
                        },
                        scaleTransformCallback,
                        () -> confirmedScaleTranslate,
                        (x) -> { confirmedScaleTranslate = x; },
                        () -> doubleFingerInitialSpacing,
                        (x) -> { doubleFingerInitialSpacing = x; },
                        () -> doubleFingerInitialMidpointX,
                        (x) -> { doubleFingerInitialMidpointX = x; },
                        () -> doubleFingerInitialMidpointY,
                        (y) -> { doubleFingerInitialMidpointY = y; }
                );
            } else {
                touchContextMap[i] = new TrackpadContext(conn, i);
            }
        }

        // Always exit zoom mode if mouse mode has changed
        isPanZoomMode = false;
    }

    public void toggleHUD(){
        prefConfig.enablePerfOverlay = !prefConfig.enablePerfOverlay;
        if(prefConfig.enablePerfOverlay){
            performanceOverlayView.setVisibility(View.VISIBLE);
            if(prefConfig.enablePerfOverlayLite){
                performanceOverlayLite.setVisibility(View.VISIBLE);
            }else{
                performanceOverlayBig.setVisibility(View.VISIBLE);
            }
            return;
        }
        performanceOverlayView.setVisibility(View.GONE);
    }

    //切换触控灵敏度开关
    public void switchTouchSensitivity(){
        prefConfig.enableTouchSensitivity = !prefConfig.enableTouchSensitivity;
    }

    public void disconnect() {
        if (prefConfig.smartClipboardSync) {
            getClipboard(-1);
        }
        finish();
    }

    public void quit() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.game_dialog_title_quit_confirm);
        builder.setMessage(R.string.game_dialog_message_quit_confirm);

        builder.setPositiveButton(getString(R.string.yes), (dialog, which) -> {
            quitOnStop = true;
            dialog.dismiss();
            finish();
        });

        builder.setNegativeButton(getString(R.string.no), (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    @Override
    public void showGameMenu(GameInputDevice device) {
        if (gameMenuCallbacks != null) {
            gameMenuCallbacks.showMenu(device);
        }
    }

    public void hideGameMenu() {
        if (gameMenuCallbacks != null) {
            gameMenuCallbacks.hideMenu();
        }
    }

    public SecondaryDisplayPresentation presentation;
    public void showSecondScreen(){
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = displayManager.getDisplays();
        int mainDisplayId = Display.DEFAULT_DISPLAY;
        int secondaryDisplayId = -1;
        for (Display display : displays) {
//            LimeLog.info(display.toString());
            if (display.getDisplayId() != mainDisplayId) {
                secondaryDisplayId = display.getDisplayId();
                break;
            }
        }
        if (secondaryDisplayId != -1) {
            Display secondaryDisplay = displayManager.getDisplay(secondaryDisplayId);
            presentation = new SecondaryDisplayPresentation(this, secondaryDisplay);
            presentation.show();
            if(rootView!= null) {
                ((ViewGroup)rootView).removeView(streamView); // <- fix
                presentation.addView(streamView);
            }
            // Force mouse mode as trackpad during presentation as user won't see anything on device screen
            applyMouseMode(2);
        }
    }

    private void updateFloatingButtonVisibility(boolean show) {
        floatingMenuButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public void toggleFloatingButtonVisibility() {
        if (floatingMenuButton != null) {
            updateFloatingButtonVisibility(floatingMenuButton.getVisibility() == View.GONE);
        }
    }


    // 设置surfaceView的圆角 setSurfaceviewCorner(UiHelper.dpToPx(this,24));
    private void setSurfaceviewCorner(final float radius) {

        streamView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                Rect rect = new Rect();
                view.getGlobalVisibleRect(rect);
                int leftMargin = 0;
                int topMargin = 0;
                Rect selfRect = new Rect(leftMargin, topMargin, rect.right - rect.left - leftMargin, rect.bottom - rect.top - topMargin);
                outline.setRoundRect(selfRect, radius);
            }
        });
        streamView.setClipToOutline(true);
    }
}