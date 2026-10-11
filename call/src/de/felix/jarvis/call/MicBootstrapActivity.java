package de.felix.jarvis.call;

import android.app.Activity;
import android.os.Bundle;

/**
 * Fully transparent, content-less screen that exists for a split second: Android only lets an app use the
 * microphone in the background if it started that while visible. The user keeps seeing the phone app.
 */
public class MicBootstrapActivity extends Activity {
    @Override protected void onResume() {
        super.onResume();
        LinkService ls = LinkService.get();
        if (ls != null) ls.inCall(true);
        Vault.log(this, "Mikrofon unsichtbar freigegeben (" + (ls != null && ls.micReady()) + ")");
        finish();
        overridePendingTransition(0, 0);
    }
}
