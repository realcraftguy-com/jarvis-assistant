package de.felix.jarvis.call;

import android.content.Intent;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.TelecomManager;
import android.net.Uri;

final class JarvisConnection extends Connection {
    private final CallSession s;

    final boolean managed;

    JarvisConnection(CallSession s, boolean managed) {
        this.s = s;
        this.managed = managed;
        if (!managed) setConnectionProperties(PROPERTY_SELF_MANAGED);
        setConnectionCapabilities(CAPABILITY_MUTE | CAPABILITY_SUPPORT_HOLD | CAPABILITY_HOLD);
        setAudioModeIsVoip(true);
        setAddress(managed ? (CallSession.redirectionActive(s.ctx) ? CallSession.NUMBER_URI : CallSession.SIP_URI) : Uri.fromParts("jarvis", "jarvis", null),
                TelecomManager.PRESENTATION_ALLOWED);
        setCallerDisplayName("Jarvis", TelecomManager.PRESENTATION_ALLOWED);
        setInitializing();
    }

    @Override public void onShowIncomingCallUi() { s.showIncomingUi(); }

    @Override public void onAnswer() { s.answer(); }

    @Override public void onAnswer(int videoState) { s.answer(); }

    @Override public void onReject() { s.decline(); }

    @Override public void onDisconnect() { s.hangup(); }

    @Override public void onAbort() { s.hangup(); }

    @Override public void onHold() { setOnHold(); boolean m = s.muted; s.setMuted(true); s.muted = m; }

    @Override public void onUnhold() { setActive(); s.setMuted(s.muted); }

    @Override public void onCallAudioStateChanged(CallAudioState st) {
        s.speaker = st.getRoute() == CallAudioState.ROUTE_SPEAKER;
        // in the phone app's UI, mute is a button there: follow it
        if (managed && st.isMuted() != s.muted) s.setMuted(st.isMuted());
        s.changed();
    }

    void route(boolean speaker) {
        setAudioRoute(speaker ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_WIRED_OR_EARPIECE);
    }
}
