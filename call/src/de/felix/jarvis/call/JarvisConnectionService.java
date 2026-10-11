package de.felix.jarvis.call;

import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/** Self-managed calling: Android treats a Jarvis call like a real call (audio focus, routing, call chip). */
public class JarvisConnectionService extends ConnectionService {
    @Override public Connection onCreateIncomingConnection(PhoneAccountHandle h, ConnectionRequest r) {
        CallSession s = CallSession.get(this);
        JarvisConnection c = new JarvisConnection(s, h != null && "jarvis-system".equals(h.getId()));
        c.setRinging();
        s.connection = c;
        return c;
    }

    @Override public Connection onCreateOutgoingConnection(PhoneAccountHandle h, ConnectionRequest r) {
        CallSession s = CallSession.get(this);
        boolean managed = h != null && "jarvis-system".equals(h.getId());
        JarvisConnection c = new JarvisConnection(s, managed);
        // internet-style call: connected right away (some phone apps only show their call screen once active);
        // Jarvis' greeting follows within about a second
        if (managed) c.setActive(); else c.setDialing();
        if (managed && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && "1".equals(Vault.get(this, "mic_test", "0"))) {
            micTest(c); // development only: is the microphone usable while the phone app is in front?
            return c;
        }
        // microphone for calls shown in the phone app: from the call context Android allows a short Jarvis screen
        if (managed && !LinkService.micReadyNow()) s.bootstrapMic();
        if (managed && s.state != CallSession.State.DIALING) {
            // started from the phone app / contact: start the Jarvis call now
            s.startFromSystem(c);
        } else {
            s.connection = c;
        }
        return c;
    }

    private void micTest(JarvisConnection c) {
        c.setActive();
        new Thread(() -> {
            String fgs;
            LinkService ls = LinkService.get();
            try {
                if (ls == null) throw new IllegalStateException("Dienst läuft nicht");
                fgs = "FGS-Typen vorher " + Integer.toHexString(ls.getForegroundServiceType());
                if (!ls.micReady()) { CallSession.get(this).bootstrapMic(); android.os.SystemClock.sleep(1500); fgs += " → nach Freigabe " + Integer.toHexString(ls.getForegroundServiceType()); }
            } catch (Throwable t) { fgs = "FGS-Upgrade: " + t; }
            android.os.SystemClock.sleep(1500);
            android.media.AudioRecord ar = null;
            String res;
            try {
                ar = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                        android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, 32000);
                ar.startRecording();
                short[] b = new short[16000 * 4];
                int got = 0;
                while (got < b.length) { int n = ar.read(b, got, Math.min(1600, b.length - got)); if (n <= 0) break; got += n; }
                long nonZero = 0; double sum = 0;
                for (int i = 0; i < got; i++) { if (b[i] != 0) nonZero++; sum += (double) b[i] * b[i]; }
                android.media.AudioRecordingConfiguration cfg = ar.getActiveRecordingConfiguration();
                res = "Samples " + got + ", nicht-null " + nonZero + ", RMS " + Math.round(Math.sqrt(sum / Math.max(1, got)))
                        + ", stummgeschaltet=" + (cfg != null && cfg.isClientSilenced());
            } catch (Throwable t) {
                res = "Aufnahme: " + t;
            } finally {
                if (ar != null) { try { ar.stop(); } catch (Exception ignored) {} ar.release(); }
            }
            Vault.log(this, "MIKROTEST " + fgs + " | " + res);
            if (ls != null) ls.inCall(false);
            c.setDisconnected(new android.telecom.DisconnectCause(android.telecom.DisconnectCause.LOCAL));
            c.destroy();
        }).start();
    }

    @Override public void onCreateIncomingConnectionFailed(PhoneAccountHandle h, ConnectionRequest r) {
        // e.g. the Boss is in a real phone call: Telecom refuses, Jarvis gets "declined" and writes instead
        Vault.log(this, "System hat den eingehenden Jarvis-Anruf abgelehnt (anderes Gespräch aktiv?)");
        CallSession.get(this).decline();
    }

    @Override public void onCreateOutgoingConnectionFailed(PhoneAccountHandle h, ConnectionRequest r) {
        Vault.log(this, "System hat den ausgehenden Jarvis-Anruf abgelehnt");
    }
}
