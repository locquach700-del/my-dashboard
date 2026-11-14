package com.klyro.recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class RecordingActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Intent service = new Intent(context, RecordingService.class);
        service.setAction(intent.getAction());
        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }
}
