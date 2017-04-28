package com.tacttiles.runtime;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Created by andy on 22/04/17.
 */

public class ServiceBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);

        //if (intent.getAction().equals(Intent.ACTION_USER_PRESENT) || (intent.getAction().equals((Intent.ACTION_SCREEN_ON)))) {
        Toast.makeText(context, "ACTION_USER_PRESENT", Toast.LENGTH_LONG).show();

        Intent i = new Intent();
        i.setAction("com.tacttiles.runtime.RuntimeService");
        i.setPackage("com.tacttiles.runtime");
        i.putExtra("type", RuntimeService.RS_CMD.CONNECT_BT);

        context.startService(i);
    }
}
