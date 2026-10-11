package de.felix.jarvis.call;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification buttons (decline / hang up) and boot start of the link service. */
public class CallActions extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction() == null ? "" : i.getAction();
        switch (a) {
            case "decline": CallSession.get(c).decline(); break;
            case "hangup": CallSession.get(c).hangup(); break;
            default:
                CallSession.registerAccount(c);
                LinkService.start(c);
        }
    }
}
