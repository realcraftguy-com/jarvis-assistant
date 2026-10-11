package de.felix.jarvis.call;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * "Jarvis anrufen" from the quick-settings tile or the home-screen shortcut: invisible, starts the call and is gone.
 * The phone app shows the call (or Jarvis' own call screen if the phone-app mode is off).
 */
public class QuickCallActivity extends Activity {
    @Override protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this, MainActivity.class)); // asks for the microphone there
        } else {
            LinkService.start(this);
            LinkService ls = LinkService.get();
            if (ls != null) ls.inCall(true); // visible right now: background microphone use is allowed from here on
            CallSession.get(getApplicationContext()).startOutgoing();
            if (!CallSession.systemEnabled(this)) startActivity(new Intent(this, InCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Vault.log(this, "Anruf über Kachel/Verknüpfung");
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
