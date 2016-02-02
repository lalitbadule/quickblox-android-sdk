package com.quickblox.simplesample.messages.gcm;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;
import com.quickblox.core.QBEntityCallbackImpl;
import com.quickblox.messages.QBMessages;
import com.quickblox.messages.model.QBEnvironment;
import com.quickblox.messages.model.QBSubscription;
import com.quickblox.sample.core.utils.VersionUtils;
import com.quickblox.simplesample.messages.App;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class GooglePlayServicesHelper {
    private static final String TAG = GooglePlayServicesHelper.class.getSimpleName();

    private static final String PREF_APP_VERSION = "appVersion";
    private static final String PREF_GCM_REG_ID = "registration_id";

    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;

    public void registerForGcmIfPossible(Activity activity, String senderId) {
        // Check device for Play Services APK.
        // If check succeeds, proceed with GCM registration.
        if (checkPlayServices(activity)) {
            String gcmRegId = getGcmRegIdFromPreferences();
            if (TextUtils.isEmpty(gcmRegId)) {
                registerInGcmInBackground(senderId);
            }
        } else {
            Log.i(TAG, "No valid Google Play Services APK found.");
        }
    }

    /**
     * Check the device to make sure it has the Google Play Services APK. If
     * it doesn't, display a dialog that allows users to download the APK from
     * the Google Play Store or enable it in the device's system settings.
     *
     * @param activity activity where you check Google Play Services availability
     */
    public boolean checkPlayServices(Activity activity) {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        int resultCode = apiAvailability.isGooglePlayServicesAvailable(activity);
        if (resultCode != ConnectionResult.SUCCESS) {
            if (apiAvailability.isUserResolvableError(resultCode)) {
                apiAvailability.getErrorDialog(activity, resultCode, PLAY_SERVICES_RESOLUTION_REQUEST)
                        .show();
            } else {
                Log.i(TAG, "This device is not supported.");
                activity.finish();
            }
            return false;
        }
        return true;
    }

    /**
     * Registers the application with GCM servers asynchronously.
     * <p/>
     * Stores the registration ID and app versionCode in the application's
     * shared preferences.
     */
    private void registerInGcmInBackground(String senderId) {
        new AsyncTask<String, Void, String>() {
            @Override
            protected String doInBackground(String... params) {
                try {
                    InstanceID instanceID = InstanceID.getInstance(App.getInstance());
                    String token = instanceID.getToken(params[0], GoogleCloudMessaging.INSTANCE_ID_SCOPE, null);
                    return token;
                } catch (IOException e) {
                    // If there is an error, don't just keep trying to register.
                    // Require the user to click a button again, or perform
                    // exponential back-off.
                    Log.w(TAG, e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String gcmRegId) {
                if (TextUtils.isEmpty(gcmRegId)) {
                    Log.w(TAG, "Device wasn't registered in GCM");
                } else {
                    Log.i(TAG, "Device registered in GCM, regId=" + gcmRegId);
                    subscribeToQbPushNotifications(gcmRegId);
                    saveGcmRegIdToPreferences(gcmRegId);
                }
            }
        }.execute(senderId);
    }

    /**
     * @return Application's {@code SharedPreferences}
     */
    private SharedPreferences getSharedPreferences() {
        // This sample app persists gcmRegistrationId in shared preferences,
        // but how you store gcmRegistrationId in your app is up to you
        Context context = App.getInstance();
        return context.getSharedPreferences(context.getPackageName(), Context.MODE_PRIVATE);
    }

    /**
     * Subscribe to Push Notifications
     *
     * @param gcmRegId registration ID
     */
    private void subscribeToQbPushNotifications(String gcmRegId) {
        TelephonyManager telephonyManager = (TelephonyManager) App.getInstance()
                .getSystemService(Context.TELEPHONY_SERVICE);
        String uniqueDeviceId = telephonyManager.getDeviceId();
        if (TextUtils.isEmpty(uniqueDeviceId)) {
            ContentResolver cr = App.getInstance().getContentResolver();
            uniqueDeviceId = Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID); // for tablets
        }

        // Don't forget to change QBEnvironment environment to PRODUCTION when releasing application
        QBMessages.subscribeToPushNotificationsTask(gcmRegId, uniqueDeviceId, QBEnvironment.DEVELOPMENT,
                new QBEntityCallbackImpl<ArrayList<QBSubscription>>() {
                    @Override
                    public void onSuccess(ArrayList<QBSubscription> qbSubscriptions, Bundle bundle) {
                        Log.i(TAG, "Successfully subscribed for QB push messages");
                    }

                    @Override
                    public void onError(List<String> errors) {
                        Log.w(TAG, "Unable to subscribe for QB push messages; " + errors.toString());
                    }
                });
    }

    /**
     * Stores the registration ID and app versionCode in the application's
     * {@code SharedPreferences}.
     *
     * @param gcmRegId registration ID
     */
    private void saveGcmRegIdToPreferences(String gcmRegId) {
        int appVersion = VersionUtils.getAppVersion();
        Log.i(TAG, "Saving gcmRegId on app version " + appVersion);

        // We save both gcmRegId and current app version,
        // so we can check if app was updated next time we need to get gcmRegId
        SharedPreferences preferences = getSharedPreferences();
        preferences.edit()
                .putString(PREF_GCM_REG_ID, gcmRegId)
                .putInt(PREF_APP_VERSION, appVersion)
                .apply();
    }

    /**
     * Gets the current registration ID for application on GCM service.
     * <p/>
     * If result is empty, the app needs to register.
     *
     * @return registration ID, or empty string if there is no existing
     * registration ID or app was updated since the last gcm registration.
     */
    private String getGcmRegIdFromPreferences() {
        SharedPreferences prefs = getSharedPreferences();

        // Check if app was updated; if so, we must request new gcmRegId
        // since the existing gcmRegId is not guaranteed to work
        // with the new app version
        int registeredVersion = prefs.getInt(PREF_APP_VERSION, Integer.MIN_VALUE);
        int currentVersion = VersionUtils.getAppVersion();
        if (registeredVersion != currentVersion) {
            Log.i(TAG, "App version changed.");
            return "";
        }

        return prefs.getString(PREF_GCM_REG_ID, "");
    }
}