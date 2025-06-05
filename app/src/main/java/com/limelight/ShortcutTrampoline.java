package com.limelight;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class ShortcutTrampoline extends Activity {
    private PreferenceConfiguration prefConfig;
    private String uuidString;
    private NvApp app;
    private ArrayList<Intent> intentStack = new ArrayList<>();

    private int wakeHostTries = 10;
    private ComputerDetails computer;
    private SpinnerDialog blockingLoadSpinner;

    private ComputerManagerService.ComputerManagerBinder managerBinder;

    private static final String TAG = "ShortcutTrampoline";

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Get the computer object
                    computer = managerBinder.getComputer(uuidString);

                    if (computer == null) {
                        Dialog.displayDialog(ShortcutTrampoline.this,
                                getResources().getString(R.string.conn_error_title),
                                getResources().getString(R.string.scut_pc_not_found),
                                true);

                        if (blockingLoadSpinner != null) {
                            blockingLoadSpinner.dismiss();
                            blockingLoadSpinner = null;
                        }

                        if (managerBinder != null) {
                            unbindService(serviceConnection);
                            managerBinder = null;
                        }

                        return;
                    }

                    // Force CMS to repoll this machine
                    managerBinder.invalidateStateForComputer(computer.uuid);

                    // Start polling
                    managerBinder.startPolling(new ComputerManagerListener() {
                        @Override
                        public void notifyComputerUpdated(final ComputerDetails details) {
                            // Don't care about other computers
                            if (!details.uuid.equalsIgnoreCase(uuidString)) {
                                return;
                            }

                            // Try to wake the target PC if it's offline (up to some retry limit)
                            if (details.state == ComputerDetails.State.OFFLINE && details.macAddress != null && --wakeHostTries >= 0) {
                                try {
                                    // Make a best effort attempt to wake the target PC
                                    WakeOnLanSender.sendWolPacket(computer);

                                    // If we sent at least one WoL packet, reset the computer state
                                    // to force ComputerManager to poll it again.
                                    managerBinder.invalidateStateForComputer(computer.uuid);
                                    return;
                                } catch (IOException e) {
                                    // If we got an exception, we couldn't send a single WoL packet,
                                    // so fallthrough into the offline error path.
                                    e.printStackTrace();
                                }
                            }

                            if (details.state != ComputerDetails.State.UNKNOWN) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        // Stop showing the spinner
                                        if (blockingLoadSpinner != null) {
                                            blockingLoadSpinner.dismiss();
                                            blockingLoadSpinner = null;
                                        }

                                        // If the managerBinder was destroyed before this callback,
                                        // just finish the activity.
                                        if (managerBinder == null) {
                                            finish();
                                            return;
                                        }

                                        if (details.state == ComputerDetails.State.ONLINE && details.pairState == PairingManager.PairState.PAIRED) {
                                            
                                            // Launch game if provided app ID, otherwise launch app view
                                            if (app != null) {
                                                if (details.runningGameId == 0 || details.runningGameId == app.getAppId() || Objects.equals(details.runningGameUUID, app.getAppUUID())) {
                                                    intentStack.add(ServerHelper.createStartIntent(ShortcutTrampoline.this, app, details, managerBinder, prefConfig.useVirtualDisplay));

                                                    // Close this activity
                                                    finish();

                                                    // Now start the activities
                                                    startActivities(intentStack.toArray(new Intent[]{}));
                                                } else {
                                                    // Create the start intent immediately, so we can safely unbind the managerBinder
                                                    // below before we return.
                                                    final Intent startIntent = ServerHelper.createStartIntent(ShortcutTrampoline.this, app, details, managerBinder, prefConfig.useVirtualDisplay);

                                                    UiHelper.displayQuitConfirmationDialog(ShortcutTrampoline.this, new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            intentStack.add(startIntent);

                                                            // Close this activity
                                                            finish();

                                                            // Now start the activities
                                                            startActivities(intentStack.toArray(new Intent[]{}));
                                                        }
                                                    }, new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            // Close this activity
                                                            finish();
                                                        }
                                                    });
                                                }
                                            } else {
                                                // Close this activity
                                                finish();

                                                // Add the PC view at the back (and clear the task)
                                                Intent i;
                                                i = new Intent(ShortcutTrampoline.this, PcView.class);
                                                i.setAction(Intent.ACTION_MAIN);
                                                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                                                intentStack.add(i);

                                                // Take this intent's data and create an intent to start the app view
                                                i = new Intent(getIntent());
                                                i.setClass(ShortcutTrampoline.this, AppView.class);
                                                intentStack.add(i);

                                                // If a game is running, we'll make the stream the top level activity
                                                if (details.runningGameId != 0) {
                                                    intentStack.add(ServerHelper.createStartIntent(ShortcutTrampoline.this,
                                                            new NvApp(null, null, details.runningGameId, false), details, managerBinder, prefConfig.useVirtualDisplay));
                                                }

                                                // Now start the activities
                                                startActivities(intentStack.toArray(new Intent[]{}));
                                            }
                                            
                                        }
                                        else if (details.state == ComputerDetails.State.OFFLINE) {
                                            // Computer offline - display an error dialog
                                            Dialog.displayDialog(ShortcutTrampoline.this,
                                                    getResources().getString(R.string.conn_error_title),
                                                    getResources().getString(R.string.error_pc_offline),
                                                    true);
                                        } else if (details.pairState != PairingManager.PairState.PAIRED) {
                                            // Computer not paired - display an error dialog
                                            Dialog.displayDialog(ShortcutTrampoline.this,
                                                    getResources().getString(R.string.conn_error_title),
                                                    getResources().getString(R.string.scut_not_paired),
                                                    true);
                                        }

                                        // We don't want any more callbacks from now on, so go ahead
                                        // and unbind from the service
                                        if (managerBinder != null) {
                                            managerBinder.stopPolling();
                                            unbindService(serviceConnection);
                                            managerBinder = null;
                                        }
                                    }
                                });
                            }
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    protected boolean validateInput(String hostUUID, String hostName, String appUUID, String appIDStr, String appName) {
        // Validate PC UUID/Name
        if (hostUUID == null && hostName == null) {
            Dialog.displayDialog(ShortcutTrampoline.this,
                    getResources().getString(R.string.conn_error_title),
                    getResources().getString(R.string.scut_invalid_uuid),
                    true);
            return false;
        }

        if (hostUUID != null && !hostUUID.isEmpty()) {
            try {
                UUID.fromString(hostUUID);
            } catch (IllegalArgumentException ex) {
                Dialog.displayDialog(ShortcutTrampoline.this,
                        getResources().getString(R.string.conn_error_title),
                        getResources().getString(R.string.scut_invalid_uuid),
                        true);
                return false;
            }
        } else {
            // UUID is null, so fallback to Name
            if (hostName == null || hostName.isEmpty()) {
                Dialog.displayDialog(ShortcutTrampoline.this,
                        getResources().getString(R.string.conn_error_title),
                        getResources().getString(R.string.scut_invalid_uuid),
                        true);
                return false;
            }
        }

        if (appUUID == null && appIDStr == null && appName == null) {
            Dialog.displayDialog(ShortcutTrampoline.this,
                    getResources().getString(R.string.conn_error_title),
                    getResources().getString(R.string.scut_invalid_app_id),
                    true);
            return false;
        }

        if (appUUID != null && !appUUID.isEmpty()) {
            try {
                UUID.fromString(appUUID);
            } catch (IllegalArgumentException ex) {
                Dialog.displayDialog(ShortcutTrampoline.this,
                        getResources().getString(R.string.conn_error_title),
                        getResources().getString(R.string.scut_invalid_app_id),
                        true);
                return false;
            }
        } else {
            // Validate App ID (if provided)
            if (appIDStr != null && !appIDStr.isEmpty()) {
                try {
                    Integer.parseInt(appIDStr);
                } catch (NumberFormatException ex) {
                    Dialog.displayDialog(ShortcutTrampoline.this,
                            getResources().getString(R.string.conn_error_title),
                            getResources().getString(R.string.scut_invalid_app_id),
                            true);
                    return false;
                }
            }
        }

        return true;
    }

    private Map<String, String> parseArtFileData(Uri fileUri) {
        if (fileUri == null) {
            return null;
        }

        Map<String, String> artData = new HashMap<>();

        try (InputStream inputStream = getContentResolver().openInputStream(fileUri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) {
                    continue; // Skip comments and empty lines
                }
                int separatorIndex = line.indexOf(' ');
                if (separatorIndex > 0 && separatorIndex < line.length() - 1) {
                    String key = line.substring(0, separatorIndex).trim();
                    String value = line.substring(separatorIndex + 1).trim();
                    if (key.startsWith("[") && key.endsWith("]")) {
                        key = key.substring(1, key.length() - 1);
                        artData.put(key, value);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading .art file", e);
            Dialog.displayDialog(ShortcutTrampoline.this,
                    getResources().getString(R.string.conn_error_title),
                    "Error reading .art file: " + e.getMessage(),
                    true);
        }
        return artData;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefConfig = PreferenceConfiguration.readPreferences(this);

        UiHelper.notifyNewRootView(this);
        ComputerDatabaseManager dbManager = new ComputerDatabaseManager(this);
        ComputerDetails _computer = null;

        Intent intent = getIntent();
        String action = intent.getAction();
        Uri dataUri = intent.getData();

        String hostUUID = null;
        String hostName = null;
        String appName = null;
        String appUUID = null;
        String appIDStr = null;

        if (Intent.ACTION_VIEW.equals(action) && dataUri != null) {
            Map<String, String> artData = parseArtFileData(dataUri);

            if (artData != null) {
                hostUUID = artData.get(ShortcutHelper.KEY_HOST_UUID);
                hostName = artData.get(ShortcutHelper.KEY_HOST_NAME);
                appName = artData.get(ShortcutHelper.KEY_APP_NAME);
                appUUID = artData.get(ShortcutHelper.KEY_APP_UUID);
                appIDStr = artData.get(ShortcutHelper.KEY_APP_ID);
            }
        }

        {
            // PC arguments, both are optional, but at least one must be provided
            if (hostUUID == null) {
                hostUUID = getIntent().getStringExtra(AppView.UUID_EXTRA);
            }
            if (hostName == null) {
                hostName = getIntent().getStringExtra(AppView.NAME_EXTRA);
            }

            // App arguments, all optional, but one must be provided in order to start an app
            if (appUUID == null) {
                appUUID = getIntent().getStringExtra(Game.EXTRA_APP_UUID);
            }
            if (appIDStr == null) {
                appIDStr = getIntent().getStringExtra(Game.EXTRA_APP_ID);
            }
            if (appName == null) {
                appName = getIntent().getStringExtra(Game.EXTRA_APP_NAME);
            }
        }

        if (!validateInput(hostUUID, hostName, appUUID, appIDStr, appName)) {
            // Invalid input, so just return
//            finish();
            return;
        }

        if (hostUUID == null || hostUUID.isEmpty()) {
            // Use hostName to find the corresponding UUID
            _computer = dbManager.getComputerByName(hostName);

            if (_computer == null) {
                Dialog.displayDialog(ShortcutTrampoline.this,
                        getResources().getString(R.string.conn_error_title),
                        getResources().getString(R.string.scut_pc_not_found),
                        true);
//                    finish();
                return;
            }

            hostUUID = _computer.uuid;
        }

        uuidString = hostUUID;

        // Set the AppView UUID intent
        setIntent(new Intent(getIntent()).putExtra(AppView.UUID_EXTRA, uuidString));

        // If app data came from .art file or was determined by appNameString from extras
        if (appUUID != null && !appUUID.isEmpty()) {
            app = new NvApp(appName, // appName can be null if only UUID is provided
                    appUUID,
                    -1, // App ID is not strictly needed if UUID is present
                    getIntent().getBooleanExtra(Game.EXTRA_APP_HDR, false)); // HDR info still from intent
        } else if (appIDStr != null && !appIDStr.isEmpty()) {
            int appID = Integer.parseInt(appIDStr);
            app = new NvApp(appName, // appName can be null if only App ID is provided
                    null,
                    appID,
                    getIntent().getBooleanExtra(Game.EXTRA_APP_HDR, false)); // HDR info still from intent
        } else if (appName != null && !appName.isEmpty()) {
            // Use appNameString (from .art file or intent extra) to find the corresponding AppId and AppUUID
            try {
                int appID = -1;
                String appUuidFromFile = null;
                String rawAppList = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));

                if (rawAppList.isEmpty()) {
                    Dialog.displayDialog(ShortcutTrampoline.this,
                            getResources().getString(R.string.conn_error_title),
                            getResources().getString(R.string.scut_invalid_app_id) + " (applist cache empty or unreadable)",
                            true);
//                    finish();
                    return;
                }
                List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(rawAppList));

                for (NvApp _app : applist) {
                    if (_app.getAppName().equalsIgnoreCase(appName)) {
                        appID = _app.getAppId();
                        appUuidFromFile = _app.getAppUUID();
                        break;
                    }
                }
                if (appID < 0 && appUuidFromFile == null) { // Need at least one
                    Dialog.displayDialog(ShortcutTrampoline.this,
                            getResources().getString(R.string.conn_error_title),
                            getResources().getString(R.string.scut_invalid_app_id) + " (app not found in cache)",
                            true);
//                    finish();
                    return;
                }
                // Update intent with found app ID and UUID if they weren't originally there
                Intent currentIntent = getIntent();
                if (currentIntent.getStringExtra(Game.EXTRA_APP_ID) == null && appID != -1) {
                    currentIntent.putExtra(Game.EXTRA_APP_ID, String.valueOf(appID));
                }
                if (currentIntent.getStringExtra(Game.EXTRA_APP_UUID) == null && appUuidFromFile != null) {
                    currentIntent.putExtra(Game.EXTRA_APP_UUID, appUuidFromFile);
                }
                app = new NvApp(
                        appName,
                        appUuidFromFile,
                        appID,
                        getIntent().getBooleanExtra(Game.EXTRA_APP_HDR, false));
            } catch (IOException | XmlPullParserException e) {
                Log.e(TAG, "Error processing app list from cache", e);
                Dialog.displayDialog(ShortcutTrampoline.this,
                        getResources().getString(R.string.conn_error_title),
                        getResources().getString(R.string.scut_invalid_app_id) + " (error parsing applist cache)",
                        true);
//                finish();
                return;
            }
        }

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.conn_establishing_title),
                getResources().getString(R.string.applist_connect_msg), true);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (blockingLoadSpinner != null) {
            blockingLoadSpinner.dismiss();
            blockingLoadSpinner = null;
        }

        Dialog.closeDialogs();

        if (managerBinder != null) {
            managerBinder.stopPolling();
            unbindService(serviceConnection);
            managerBinder = null;
        }

        finish();
    }
}
