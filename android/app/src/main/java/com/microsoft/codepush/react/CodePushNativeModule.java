package com.microsoft.codepush.react;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactRootView;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.CatalystInstance;
import com.facebook.react.bridge.JSBundleLoader;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.ChoreographerCompat;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.ReactChoreographer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CodePushNativeModule extends ReactContextBaseJavaModule {
    private String mBinaryContentsHash = null;
    private String mClientUniqueId = null;
    private LifecycleEventListener mLifecycleEventListener = null;
    private int mMinimumBackgroundDuration = 0;

    private CodePush mCodePush;
    private SettingsManager mSettingsManager;
    private CodePushTelemetryManager mTelemetryManager;
    private CodePushUpdateManager mUpdateManager;

    private  boolean _allowed = true;
    private  boolean _restartInProgress = false;
    private  ArrayList<Boolean> _restartQueue = new ArrayList<>();
         private final String CODE_PUSH_RESTART_APP = "CODE_PUSH_RESTART_APP";


    public CodePushNativeModule(ReactApplicationContext reactContext, CodePush codePush, CodePushUpdateManager codePushUpdateManager, CodePushTelemetryManager codePushTelemetryManager, SettingsManager settingsManager) {
        super(reactContext);

        mCodePush = codePush;
        mSettingsManager = settingsManager;
        mTelemetryManager = codePushTelemetryManager;
        mUpdateManager = codePushUpdateManager;

        // Initialize module state while we have a reference to the current context.
        mBinaryContentsHash = CodePushUpdateUtils.getHashForBinaryContents(reactContext, mCodePush.isDebugMode());
        mClientUniqueId = Settings.Secure.getString(reactContext.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();

        constants.put("codePushInstallModeImmediate", CodePushInstallMode.IMMEDIATE.getValue());
        constants.put("codePushInstallModeOnNextRestart", CodePushInstallMode.ON_NEXT_RESTART.getValue());
        constants.put("codePushInstallModeOnNextResume", CodePushInstallMode.ON_NEXT_RESUME.getValue());
        constants.put("codePushInstallModeOnNextSuspend", CodePushInstallMode.ON_NEXT_SUSPEND.getValue());

        constants.put("codePushUpdateStateRunning", CodePushUpdateState.RUNNING.getValue());
        constants.put("codePushUpdateStatePending", CodePushUpdateState.PENDING.getValue());
        constants.put("codePushUpdateStateLatest", CodePushUpdateState.LATEST.getValue());

        return constants;
    }

    @Override
    public String getName() {
        return "CodePush";
    }

    private void loadBundleLegacy() {
        final Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            // The currentActivity can be null if it is backgrounded / destroyed, so we simply
            // no-op to prevent any null pointer exceptions.
            return;
        }
        mCodePush.invalidateCurrentInstance();

        currentActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                currentActivity.recreate();
            }
        });
    }

    // Use reflection to find and set the appropriate fields on ReactInstanceManager. See #556 for a proposal for a less brittle way
    // to approach this.
    private void setJSBundle(ReactInstanceManager instanceManager, String latestJSBundleFile) throws IllegalAccessException {
        try {
            JSBundleLoader latestJSBundleLoader;
            if (latestJSBundleFile.toLowerCase().startsWith("assets://")) {
                latestJSBundleLoader = JSBundleLoader.createAssetLoader(getReactApplicationContext(), latestJSBundleFile, false);
            } else {
                latestJSBundleLoader = JSBundleLoader.createFileLoader(latestJSBundleFile);
            }

            Field bundleLoaderField = instanceManager.getClass().getDeclaredField("mBundleLoader");
            bundleLoaderField.setAccessible(true);
            bundleLoaderField.set(instanceManager, latestJSBundleLoader);
        } catch (Exception e) {
            CodePushUtils.log("Unable to set JSBundle - CodePush may not support this version of React Native");
            throw new IllegalAccessException("Could not setJSBundle");
        }
    }

    private void loadBundle(String pathPrefix, String bundleFileName) {
        System.err.println("[CodePush] NativeModule loadBundle: pathPrefix = " + pathPrefix + "  bundleFileName = " + bundleFileName);

        clearLifecycleEventListener();
        try {
            mCodePush.clearDebugCacheIfNeeded(resolveInstanceManager(), pathPrefix);
        } catch(Exception e) {
            // If we got error in out reflection we should clear debug cache anyway.
            mCodePush.clearDebugCacheIfNeeded(null, pathPrefix);
        }

        try {
            // #1) Get the ReactInstanceManager instance, which is what includes the
            //     logic to reload the current React context.
            final ReactInstanceManager instanceManager = resolveInstanceManager();
            if (instanceManager == null) {
                return;
            }

            // String latestJSBundleFile = mCodePush.getJSBundleFileInternal(mCodePush.getAssetsBundleFileName());

            String latestJSBundleFile = mCodePush.getJSBundleFileInternal(bundleFileName, pathPrefix);

            // #2) Update the locally stored JS bundle file path
            setJSBundle(instanceManager, latestJSBundleFile);

            // #3) Get the context creation method and fire it on the UI thread (which RN enforces)
            // new Handler(Looper.getMainLooper()).post(new Runnable() {
            //     @Override
            //     public void run() {
            //         try {
            //             // We don't need to resetReactRootViews anymore 
            //             // due the issue https://github.com/facebook/react-native/issues/14533
            //             // has been fixed in RN 0.46.0
            //             //resetReactRootViews(instanceManager);

            //             instanceManager.recreateReactContextInBackground();
            //             mCodePush.initializeUpdateAfterRestart(pathPrefix);
            //         } catch (Exception e) {
            //             // The recreation method threw an unknown exception
            //             // so just simply fallback to restarting the Activity (if it exists)
            //             loadBundleLegacy();
            //         }
            //     }
            // });

            JSBundleLoader latestJSBundleLoader;
             if (latestJSBundleFile.toLowerCase().startsWith("assets://")) {
                latestJSBundleLoader = JSBundleLoader.createAssetLoader(getReactApplicationContext(), latestJSBundleFile, false);
            } else {
                latestJSBundleLoader = JSBundleLoader.createFileLoader(latestJSBundleFile);
            }
//            CatalystInstance catalystInstance = instanceManager.getCurrentReactContext().getCatalystInstance();
//            latestJSBundleLoader.loadScript(catalystInstance);
            mCodePush.initializeUpdateAfterRestart(pathPrefix);
            WritableMap map = Arguments.createMap();
            map.putString("pathPrefix", pathPrefix);
            map.putString("bundleFileName", bundleFileName);
            getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(CODE_PUSH_RESTART_APP, map);
        } catch (Exception e) {
            // Our reflection logic failed somewhere
            // so fall back to restarting the Activity (if it exists)
            CodePushUtils.log("Failed to load the bundle, falling back to restarting the Activity (if it exists). " + e.getMessage());
            loadBundleLegacy();
        }
    }

    // This workaround has been implemented in order to fix https://github.com/facebook/react-native/issues/14533
    // resetReactRootViews allows to call recreateReactContextInBackground without any exceptions
    // This fix also relates to https://github.com/microsoft/react-native-code-push/issues/878
    private void resetReactRootViews(ReactInstanceManager instanceManager) throws NoSuchFieldException, IllegalAccessException {
        Field mAttachedRootViewsField = instanceManager.getClass().getDeclaredField("mAttachedRootViews");
        mAttachedRootViewsField.setAccessible(true);
        List<ReactRootView> mAttachedRootViews = (List<ReactRootView>)mAttachedRootViewsField.get(instanceManager);
        for (ReactRootView reactRootView : mAttachedRootViews) {
            reactRootView.removeAllViews();
            reactRootView.setId(View.NO_ID);
        }
        mAttachedRootViewsField.set(instanceManager, mAttachedRootViews);
    }

    private void clearLifecycleEventListener() {
        // Remove LifecycleEventListener to prevent infinite restart loop
        if (mLifecycleEventListener != null) {
            getReactApplicationContext().removeLifecycleEventListener(mLifecycleEventListener);
            mLifecycleEventListener = null;
        }
    }

    // Use reflection to find the ReactInstanceManager. See #556 for a proposal for a less brittle way to approach this.
    private ReactInstanceManager resolveInstanceManager() throws NoSuchFieldException, IllegalAccessException {
        ReactInstanceManager instanceManager = CodePush.getReactInstanceManager();
        if (instanceManager != null) {
            return instanceManager;
        }

        final Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            return null;
        }

        ReactApplication reactApplication = (ReactApplication) currentActivity.getApplication();
        instanceManager = reactApplication.getReactNativeHost().getReactInstanceManager();

        return instanceManager;
    }

    private void restartAppInternal(boolean onlyIfUpdateIsPending, String pathPrefix, String bundleFileName) {
        if (this._restartInProgress) {
            CodePushUtils.log("Restart request queued until the current restart is completed");
            this._restartQueue.add(onlyIfUpdateIsPending);
            return;
        } else if (!this._allowed) {
            CodePushUtils.log("Restart request queued until restarts are re-allowed");
            this._restartQueue.add(onlyIfUpdateIsPending);
            return;
        }

        this._restartInProgress = true;
        if (!onlyIfUpdateIsPending || mSettingsManager.isPendingUpdate(null, pathPrefix)) {
            loadBundle(pathPrefix, bundleFileName);
            CodePushUtils.log("Restarting app");
            return;
        }

        this._restartInProgress = false;
        if (this._restartQueue.size() > 0) {
            boolean buf = this._restartQueue.get(0);
            this._restartQueue.remove(0);
            this.restartAppInternal(buf, pathPrefix, bundleFileName);
        }
    }

    @ReactMethod
    public void allow(Promise promise) {
        System.err.println("[CodePush] NativeModule allow: ");

        CodePushUtils.log("Re-allowing restarts");
        this._allowed = true;

        if (_restartQueue.size() > 0) {
            CodePushUtils.log("Executing pending restart");
            boolean buf = this._restartQueue.get(0);
            this._restartQueue.remove(0);
            this.restartAppInternal(buf, null, null);
        }

        promise.resolve(null);
        return;
    }

    @ReactMethod
    public void clearPendingRestart(Promise promise) {
        System.err.println("[CodePush] NativeModule clearPendingRestart: ");
        this._restartQueue.clear();
        promise.resolve(null);
        return;
    }

    @ReactMethod
    public void disallow(Promise promise) {
        System.err.println("[CodePush] NativeModule disallow: ");
        CodePushUtils.log("Disallowing restarts");
        this._allowed = false;
        promise.resolve(null);
        return;
    }

    @ReactMethod
    public void restartApp(boolean onlyIfUpdateIsPending, String pathPrefix, String bundleFileName, Promise promise) {
        System.err.println("[CodePush] NativeModule restartApp: pathPrefix = " + pathPrefix + "  bundleFileName = " + bundleFileName);
        try {
            // restartAppInternal(onlyIfUpdateIsPending, pathPrefix, bundleFileName);
            if (!onlyIfUpdateIsPending || mSettingsManager.isPendingUpdate(null, pathPrefix)) {
                loadBundle(pathPrefix, bundleFileName);
                promise.resolve(true);
                return;
            }
            promise.resolve(null);
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void downloadUpdate(final ReadableMap updatePackage, final boolean notifyProgress, String pathPrefix, String bundleFileName, final Promise promise) {
        System.err.println("[CodePush] NativeModule downloadUpdate: pathPrefix = " + pathPrefix + "  bundleFileName = " + bundleFileName);

        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    JSONObject mutableUpdatePackage = CodePushUtils.convertReadableToJsonObject(updatePackage);
                    CodePushUtils.setJSONValueForKey(mutableUpdatePackage, CodePushConstants.BINARY_MODIFIED_TIME_KEY, "" + mCodePush.getBinaryResourcesModifiedTime());
                    mUpdateManager.downloadPackage(mutableUpdatePackage, bundleFileName, new DownloadProgressCallback() {
                        private boolean hasScheduledNextFrame = false;
                        private DownloadProgress latestDownloadProgress = null;

                        @Override
                        public void call(DownloadProgress downloadProgress) {
                            if (!notifyProgress) {
                                return;
                            }

                            latestDownloadProgress = downloadProgress;
                            // If the download is completed, synchronously send the last event.
                            if (latestDownloadProgress.isCompleted()) {
                                dispatchDownloadProgressEvent();
                                return;
                            }

                            if (hasScheduledNextFrame) {
                                return;
                            }

                            hasScheduledNextFrame = true;
                            getReactApplicationContext().runOnUiQueueThread(new Runnable() {
                                @Override
                                public void run() {
                                    ReactChoreographer.getInstance().postFrameCallback(ReactChoreographer.CallbackType.TIMERS_EVENTS, new ChoreographerCompat.FrameCallback() {
                                        @Override
                                        public void doFrame(long frameTimeNanos) {
                                            if (!latestDownloadProgress.isCompleted()) {
                                                dispatchDownloadProgressEvent();
                                            }

                                            hasScheduledNextFrame = false;
                                        }
                                    });
                                }
                            });
                        }

                        public void dispatchDownloadProgressEvent() {
                            getReactApplicationContext()
                                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                    .emit(CodePushConstants.DOWNLOAD_PROGRESS_EVENT_NAME, latestDownloadProgress.createWritableMap());
                        }
                    }, mCodePush.getPublicKey(), pathPrefix);

                    JSONObject newPackage = mUpdateManager.getPackage(CodePushUtils.tryGetString(updatePackage, CodePushConstants.PACKAGE_HASH_KEY), pathPrefix);
                    promise.resolve(CodePushUtils.convertJsonObjectToWritable(newPackage));
                } catch (CodePushInvalidUpdateException e) {
                    CodePushUtils.log(e);
                    mSettingsManager.saveFailedUpdate(CodePushUtils.convertReadableToJsonObject(updatePackage), pathPrefix);
                    promise.reject(e);
                } catch (IOException | CodePushUnknownException e) {
                    CodePushUtils.log(e);
                    promise.reject(e);
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void getConfiguration(Promise promise) {
        System.err.println("[CodePush] NativeModule getConfiguration");

        try {
            WritableMap configMap =  Arguments.createMap();
            configMap.putString("appVersion", mCodePush.getAppVersion());
            configMap.putString("clientUniqueId", mClientUniqueId);
            configMap.putString("deploymentKey", mCodePush.getDeploymentKey());
            configMap.putString("serverUrl", mCodePush.getServerUrl());

            // The binary hash may be null in debug builds
            if (mBinaryContentsHash != null) {
                configMap.putString(CodePushConstants.PACKAGE_HASH_KEY, mBinaryContentsHash);
            }

            promise.resolve(configMap);
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void getUpdateMetadata(String pathPrefix, String bundleFileName, final int updateState, final Promise promise) {
        System.err.println("[CodePush] NativeModule getUpdateMetadata:  pathPrefix = " + pathPrefix + "  bundleFileName = " + bundleFileName);

        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    JSONObject currentPackage = mUpdateManager.getCurrentPackage(pathPrefix);

                    if (currentPackage == null) {
                        promise.resolve(null);
                        return null;
                    }

                    Boolean currentUpdateIsPending = false;

                    if (currentPackage.has(CodePushConstants.PACKAGE_HASH_KEY)) {
                        String currentHash = currentPackage.optString(CodePushConstants.PACKAGE_HASH_KEY, null);
                        currentUpdateIsPending = mSettingsManager.isPendingUpdate(currentHash, pathPrefix);
                    }

                    if (updateState == CodePushUpdateState.PENDING.getValue() && !currentUpdateIsPending) {
                        // The caller wanted a pending update
                        // but there isn't currently one.
                        promise.resolve(null);
                    } else if (updateState == CodePushUpdateState.RUNNING.getValue() && currentUpdateIsPending) {
                        // The caller wants the running update, but the current
                        // one is pending, so we need to grab the previous.
                        JSONObject previousPackage = mUpdateManager.getPreviousPackage(pathPrefix);

                        if (previousPackage == null) {
                            promise.resolve(null);
                            return null;
                        }

                        promise.resolve(CodePushUtils.convertJsonObjectToWritable(previousPackage));
                    } else {
                        // The current package satisfies the request:
                        // 1) Caller wanted a pending, and there is a pending update
                        // 2) Caller wanted the running update, and there isn't a pending
                        // 3) Caller wants the latest update, regardless if it's pending or not
                        if (mCodePush.isRunningBinaryVersion(bundleFileName, pathPrefix)) {
                            // This only matters in Debug builds. Since we do not clear "outdated" updates,
                            // we need to indicate to the JS side that somehow we have a current update on
                            // disk that is not actually running.
                            CodePushUtils.setJSONValueForKey(currentPackage, "_isDebugOnly", true);
                        }

                        // Enable differentiating pending vs. non-pending updates
                        CodePushUtils.setJSONValueForKey(currentPackage, "isPending", currentUpdateIsPending);
                        promise.resolve(CodePushUtils.convertJsonObjectToWritable(currentPackage));
                    }
                } catch (CodePushMalformedDataException e) {
                    // We need to recover the app in case 'codepush.json' is corrupted
                    CodePushUtils.log(e.getMessage());
                    clearUpdates(pathPrefix);
                    promise.resolve(null);
                } catch(CodePushUnknownException e) {
                    CodePushUtils.log(e);
                    promise.reject(e);
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void getNewStatusReport(String pathPrefix, String bundleFileName, final Promise promise) {
        System.err.println("[CodePush] getNewStatusReport: pathPrefix = " + pathPrefix);
        System.err.println("[CodePush] getNewStatusReport: bundleFileName = " + bundleFileName);

        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    if (mCodePush.needToReportRollback(pathPrefix)) {
                        mCodePush.setNeedToReportRollback(false, pathPrefix);
                        JSONArray failedUpdates = mSettingsManager.getFailedUpdates(pathPrefix);
                        if (failedUpdates != null && failedUpdates.length() > 0) {
                            try {
                                JSONObject lastFailedPackageJSON = failedUpdates.getJSONObject(failedUpdates.length() - 1);
                                WritableMap lastFailedPackage = CodePushUtils.convertJsonObjectToWritable(lastFailedPackageJSON);
                                WritableMap failedStatusReport = mTelemetryManager.getRollbackReport(lastFailedPackage);
                                if (failedStatusReport != null) {
                                    promise.resolve(failedStatusReport);
                                    return null;
                                }
                            } catch (JSONException e) {
                                throw new CodePushUnknownException("Unable to read failed updates information stored in SharedPreferences.", e);
                            }
                        }
                    } else if (mCodePush.didUpdate(pathPrefix)) {
                        JSONObject currentPackage = mUpdateManager.getCurrentPackage(pathPrefix);
                        if (currentPackage != null) {
                            WritableMap newPackageStatusReport = mTelemetryManager.getUpdateReport(CodePushUtils.convertJsonObjectToWritable(currentPackage), pathPrefix);
                            if (newPackageStatusReport != null) {
                                promise.resolve(newPackageStatusReport);
                                return null;
                            }
                        }
                    } else if (mCodePush.isRunningBinaryVersion(bundleFileName, pathPrefix)) {
                        WritableMap newAppVersionStatusReport = mTelemetryManager.getBinaryUpdateReport(mCodePush.getAppVersion(), pathPrefix);
                        if (newAppVersionStatusReport != null) {
                            promise.resolve(newAppVersionStatusReport);
                            return null;
                        }
                    } else {
                        WritableMap retryStatusReport = mTelemetryManager.getRetryStatusReport(pathPrefix);
                        if (retryStatusReport != null) {
                            promise.resolve(retryStatusReport);
                            return null;
                        }
                    }
                    
                    promise.resolve("");
                } catch(CodePushUnknownException e) {
                    CodePushUtils.log(e);
                    promise.reject(e);
                }
                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void installUpdate(final ReadableMap updatePackage, final int installMode, final int minimumBackgroundDuration, String pathPrefix, String bundleFileName, final Promise promise) {
        System.err.println("[CodePush] NativeModule installUpdate:  pathPrefix = " + pathPrefix + "  bundleFileName = " + bundleFileName);

        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    mUpdateManager.installPackage(CodePushUtils.convertReadableToJsonObject(updatePackage), mSettingsManager.isPendingUpdate(null, pathPrefix), pathPrefix);

                    String pendingHash = CodePushUtils.tryGetString(updatePackage, CodePushConstants.PACKAGE_HASH_KEY);
                    if (pendingHash == null) {
                        throw new CodePushUnknownException("Update package to be installed has no hash.");
                    } else {
                        mSettingsManager.savePendingUpdate(pendingHash, /* isLoading */false, pathPrefix);
                    }

                    if (installMode == CodePushInstallMode.ON_NEXT_RESUME.getValue() ||
                        // We also add the resume listener if the installMode is IMMEDIATE, because
                        // if the current activity is backgrounded, we want to reload the bundle when
                        // it comes back into the foreground.
                        installMode == CodePushInstallMode.IMMEDIATE.getValue() ||
                        installMode == CodePushInstallMode.ON_NEXT_SUSPEND.getValue()) {

                        // Store the minimum duration on the native module as an instance
                        // variable instead of relying on a closure below, so that any
                        // subsequent resume-based installs could override it.
                        CodePushNativeModule.this.mMinimumBackgroundDuration = minimumBackgroundDuration;

                        if (mLifecycleEventListener == null) {
                            // Ensure we do not add the listener twice.
                            mLifecycleEventListener = new LifecycleEventListener() {
                                private Date lastPausedDate = null;
                                private Handler appSuspendHandler = new Handler(Looper.getMainLooper());
                                private Runnable loadBundleRunnable = new Runnable() {
                                    @Override
                                    public void run() {
                                        CodePushUtils.log("Loading bundle on suspend");
                                        // restartAppInternal(false);
                                        loadBundle(pathPrefix, bundleFileName);
                                    }
                                };

                                @Override
                                public void onHostResume() {
                                    appSuspendHandler.removeCallbacks(loadBundleRunnable);
                                    // As of RN 36, the resume handler fires immediately if the app is in
                                    // the foreground, so explicitly wait for it to be backgrounded first
                                    if (lastPausedDate != null) {
                                        long durationInBackground = (new Date().getTime() - lastPausedDate.getTime()) / 1000;
                                        if (installMode == CodePushInstallMode.IMMEDIATE.getValue()
                                                || durationInBackground >= CodePushNativeModule.this.mMinimumBackgroundDuration) {
                                            CodePushUtils.log("Loading bundle on resume");
                                            // restartAppInternal(false);
                                            loadBundle(pathPrefix, bundleFileName);
                                        }
                                    }
                                }

                                @Override
                                public void onHostPause() {
                                    // Save the current time so that when the app is later
                                    // resumed, we can detect how long it was in the background.
                                    lastPausedDate = new Date();

                                    if (installMode == CodePushInstallMode.ON_NEXT_SUSPEND.getValue() && mSettingsManager.isPendingUpdate(null, pathPrefix)) {
                                        appSuspendHandler.postDelayed(loadBundleRunnable, minimumBackgroundDuration * 1000);
                                    }
                                }

                                @Override
                                public void onHostDestroy() {
                                }
                            };

                            getReactApplicationContext().addLifecycleEventListener(mLifecycleEventListener);
                        }
                    }

                    promise.resolve("");
                } catch(CodePushUnknownException e) {
                    CodePushUtils.log(e);
                    promise.reject(e);
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void isFailedUpdate(String packageHash, String pathPrefix, Promise promise) {
        System.err.println("[CodePush] NativeModule isFailedUpdate:  pathPrefix = " + pathPrefix);

        try {
            promise.resolve(mSettingsManager.isFailedHash(packageHash, pathPrefix));
        } catch (CodePushUnknownException e) {
            CodePushUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void getLatestRollbackInfo(String pathPrefix, Promise promise) {
        System.err.println("[CodePush] NativeModule getLatestRollbackInfo:  pathPrefix = " + pathPrefix);
        try {
            JSONObject latestRollbackInfo = mSettingsManager.getLatestRollbackInfo(pathPrefix);
            if (latestRollbackInfo != null) {
                promise.resolve(CodePushUtils.convertJsonObjectToWritable(latestRollbackInfo));
            } else {
                promise.resolve(null);
            }
        } catch (CodePushUnknownException e) {
            CodePushUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void setLatestRollbackInfo(String packageHash, String pathPrefix, Promise promise) {
        System.err.println("[CodePush] NativeModule setLatestRollbackInfo:  pathPrefix = " + pathPrefix);

        try {
            mSettingsManager.setLatestRollbackInfo(packageHash, pathPrefix);
            promise.resolve(null);
        } catch (CodePushUnknownException e) {
            CodePushUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void isFirstRun(String packageHash, String pathPrefix, Promise promise) {
        System.err.println("[CodePush] NativeModule isFirstRun:  pathPrefix = " + pathPrefix);

        try {
            boolean isFirstRun = mCodePush.didUpdate(pathPrefix)
                    && packageHash != null
                    && packageHash.length() > 0
                    && packageHash.equals(mUpdateManager.getCurrentPackageHash(pathPrefix));
            promise.resolve(isFirstRun);
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void notifyApplicationReady(String pathPrefix, Promise promise) {
        System.err.println("[CodePush] NativeModule notifyApplicationReady:  pathPrefix = " + pathPrefix);

        try {
            mSettingsManager.removePendingUpdate(pathPrefix);
            promise.resolve("");
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void recordStatusReported(String pathPrefix, ReadableMap statusReport) {
        System.err.println("[CodePush] NativeModule recordStatusReported:  pathPrefix = " + pathPrefix);

        try {
            mTelemetryManager.recordStatusReported(statusReport, pathPrefix);
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e);
        }
    }

    @ReactMethod
    public void saveStatusReportForRetry(ReadableMap statusReport, String pathPrefix) {
        System.err.println("[CodePush] NativeModule saveStatusReportForRetry:  pathPrefix = " + pathPrefix);

        try {
            mTelemetryManager.saveStatusReportForRetry(statusReport, pathPrefix);
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e);
        }
    }

    @ReactMethod
    // Replaces the current bundle with the one downloaded from removeBundleUrl.
    // It is only to be used during tests. No-ops if the test configuration flag is not set.
    public void downloadAndReplaceCurrentBundle(String remoteBundleUrl, String pathPrefix, String bundleFileName) {
        try {
            if (mCodePush.isUsingTestConfiguration()) {
                try {
                    // mUpdateManager.downloadAndReplaceCurrentBundle(remoteBundleUrl, mCodePush.getAssetsBundleFileName());

                    mUpdateManager.downloadAndReplaceCurrentBundle(remoteBundleUrl, bundleFileName, pathPrefix);
                } catch (IOException e) {
                    throw new CodePushUnknownException("Unable to replace current bundle", e);
                }
            }
        } catch(CodePushUnknownException | CodePushMalformedDataException e) {
            CodePushUtils.log(e);
        }
    }

    /**
     * This method clears CodePush's downloaded updates.
     * It is needed to switch to a different deployment if the current deployment is more recent.
     * Note: we don’t recommend to use this method in scenarios other than that (CodePush will call
     * this method automatically when needed in other cases) as it could lead to unpredictable
     * behavior.
     */
    @ReactMethod
    public void clearUpdates(String pathPrefix) {
        CodePushUtils.log("Clearing updates.");
        mCodePush.clearUpdates(pathPrefix);
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Set up any upstream listeners or background tasks as necessary
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // Remove upstream listeners, stop unnecessary background tasks
    }
}
