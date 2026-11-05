package com.pooh.passcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        // Always start: the unplug safety reset must be live even in Off mode.
        Intent svc = new Intent(context, PassService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
        Pc.log(context, "Boot: service restarted (auto " + (Pc.master(context) ? "ON" : "OFF") + ")");
    }
}
