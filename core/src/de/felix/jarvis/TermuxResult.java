package de.felix.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Result of a command Jarvis ran in Termux (exit code, errors) – written to Jarvis' log. */
public class TermuxResult extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Bundle r = i.getBundleExtra("result");
        if (r == null) return;
        String msg = "Termux-Befehl beendet: Exit " + r.getInt("exitCode", -1)
                + (r.getString("errmsg") != null ? ", Fehler: " + r.getString("errmsg") : "")
                + (r.getString("stderr", "").isEmpty() ? "" : ", stderr: " + SchoolPipeline.shorten(r.getString("stderr"), 300))
                + (r.getString("stdout", "").isEmpty() ? "" : ", stdout: " + SchoolPipeline.shorten(r.getString("stdout"), 200));
        Db.get(c).log("W", "claude", msg);
        Db.get(c).kvSet("claude_error", SchoolPipeline.shorten(msg, 200));
    }
}
