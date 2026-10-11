package de.felix.jarvis.call;

import android.net.Uri;
import android.telecom.CallRedirectionService;
import android.telecom.PhoneAccountHandle;

/**
 * Safety net for Jarvis' contact number: a call to that number is redirected to Jarvis before the phone
 * dials it, so it never becomes a real (paid) mobile call. All other calls pass through unchanged.
 * Needs the role "call redirection app", granted by the Boss in the app.
 */
public class Redirector extends CallRedirectionService {
    @Override public void onPlaceCall(Uri handle, PhoneAccountHandle initial, boolean allowInteractiveResponse) {
        if (handle != null && CallSession.isJarvisNumber(handle.getSchemeSpecificPart())) {
            Vault.log(this, "Anruf an Jarvis-Nummer → zu Jarvis umgeleitet (kein Mobilfunkanruf)");
            // never dial the number; Jarvis starts the call to himself instead (shown in the phone app if enabled)
            LinkService.start(getApplicationContext());
            if (CallSession.systemEnabled(this) && !"cancel".equals(Vault.get(this, "redirect_mode", "redirect"))) {
                // same call, now routed to Jarvis' calling account: the phone app keeps showing it
                redirectCall(CallSession.SIP_URI, CallSession.systemHandle(this), false);
            } else {
                // a background service may not open screens (Android blocks it), so place Jarvis' call directly
                cancelCall();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                        CallSession.get(getApplicationContext()).startOutgoing(), 300);
            }
            return;
        }
        placeCallUnmodified();
    }
}
