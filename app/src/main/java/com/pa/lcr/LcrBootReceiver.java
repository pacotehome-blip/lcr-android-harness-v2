package com.pa.lcr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * LcrBootReceiver — BroadcastReceiver démarrage tablette
 *
 * Démarre LcrHttpService automatiquement au boot de la tablette.
 * Le chauffeur n'a pas à lancer manuellement l'APK Filgo — le serveur
 * HTTP est disponible dès que la tablette démarre.
 *
 * Chemin : app/src/main/java/com/pa/lcr/LcrBootReceiver.java
 *
 * AndroidManifest.xml — ajouter :
 *   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *
 *   <receiver
 *       android:name=".LcrBootReceiver"
 *       android:enabled="true"
 *       android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.intent.action.BOOT_COMPLETED" />
 *           <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
 *       </intent-filter>
 *   </receiver>
 *
 * Note : MY_PACKAGE_REPLACED redémarre le service après une mise à jour APK.
 */
public class LcrBootReceiver extends BroadcastReceiver {

    private static final String TAG = "LcrBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Reçu: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            Log.i(TAG, "Démarrage LcrHttpService au boot");
            startService(context);
        }
    }

    public static void startService(Context context) {
        Intent serviceIntent = new Intent(context, LcrHttpService.class);
        serviceIntent.setAction(LcrHttpService.ACTION_START);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8+ : startForegroundService obligatoire pour les foreground services
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    public static void stopService(Context context) {
        Intent serviceIntent = new Intent(context, LcrHttpService.class);
        serviceIntent.setAction(LcrHttpService.ACTION_STOP);
        context.startService(serviceIntent);
    }
}