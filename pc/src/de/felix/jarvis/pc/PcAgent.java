package de.felix.jarvis.pc;

import de.felix.jarvis.shared.Link;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Jarvis on the PC: reports the state of this computer to Jarvis (phone) and carries out a fixed list of actions
 * that Jarvis asks for (shut down, restart, sleep, lock, message, volume). There is no way to run anything else.
 * Same end-to-end encrypted link as the call app (X25519 + AES-GCM over ntfy.sh).
 *
 *   java -jar jarvis-pc.jar pair JARVISPC1.…    once, code from the Jarvis app
 *   java -jar jarvis-pc.jar run                  (the systemd user service does this)
 *   java -jar jarvis-pc.jar install              installs and starts the user service
 *   java -jar jarvis-pc.jar status               prints what Jarvis would see
 */
public final class PcAgent {
    static final File DIR = new File(System.getProperty("user.home"), ".config/jarvis-pc");
    static final File CONF = new File(DIR, "link.json");
    static final String VERSION = "1.0";

    public static void main(String[] a) throws Exception {
        String cmd = a.length == 0 ? "run" : a[0];
        switch (cmd) {
            case "pair": pair(a[1]); break;
            case "status": System.out.println(status().toString(2)); break;
            case "install": install(); break;
            case "run": run(); break;
            default: System.out.println("pair CODE | run | install | status");
        }
    }

    // ------------------------------------------------------------------ pairing / config

    static JSONObject conf() throws Exception {
        if (!CONF.exists()) throw new IllegalStateException("Noch nicht gekoppelt: java -jar jarvis-pc.jar pair JARVISPC1.…");
        return new JSONObject(new String(Files.readAllBytes(CONF.toPath()), StandardCharsets.UTF_8));
    }

    static void save(JSONObject c) throws Exception {
        DIR.mkdirs();
        Files.write(CONF.toPath(), c.toString().getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(CONF.toPath(), PosixFilePermissions.fromString("rw-------"));
    }

    static void pair(String code) throws Exception {
        if (!code.startsWith("JARVISPC1.")) throw new IllegalArgumentException("Das ist kein PC-Kopplungscode von Jarvis.");
        JSONObject j = new JSONObject(new String(Link.unb64(code.substring(10)), StandardCharsets.UTF_8));
        KeyPair kp = Link.newKeyPair();
        byte[] k = Link.sharedKey(Link.priv(kp), j.getString("k"), Link.unb64(j.getString("t")));
        String host = host();
        JSONObject hello = new JSONObject().put("t", "hello").put("pub", Link.pub(kp)).put("proof", Link.proof(k, "hello:" + j.getString("t")))
                .put("device", host.isEmpty() ? "PC" : host);
        Link.publish(j.optString("s", Link.DEFAULT_SERVER), j.getString("a"), hello.toString());
        save(new JSONObject().put("to", j.getString("a")).put("from", j.getString("b")).put("key", Link.b64(k)).put("server", j.optString("s", Link.DEFAULT_SERVER)));
        System.out.println("Gekoppelt. Jetzt: java -jar jarvis-pc.jar install");
    }

    static void install() throws Exception {
        String jar = new File(PcAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        File target = new File(DIR, "jarvis-pc.jar");
        DIR.mkdirs();
        if (!target.getAbsolutePath().equals(jar)) Files.copy(new File(jar).toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        File unit = new File(System.getProperty("user.home"), ".config/systemd/user/jarvis-pc.service");
        unit.getParentFile().mkdirs();
        String java = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
        Files.write(unit.toPath(), ("[Unit]\nDescription=Jarvis PC-Agent\nAfter=graphical-session.target network-online.target\n\n"
                + "[Service]\nExecStart=" + java + " -Xmx64m -jar " + target.getAbsolutePath() + " run\nRestart=always\nRestartSec=20\n\n"
                + "[Install]\nWantedBy=default.target\n").getBytes(StandardCharsets.UTF_8));
        System.out.println(sh(20, "systemctl", "--user", "daemon-reload") + sh(20, "systemctl", "--user", "enable", "--now", "jarvis-pc.service"));
        System.out.println("Installiert: " + unit + " (läuft bei jeder Anmeldung)");
    }

    // ------------------------------------------------------------------ main loop

    static volatile long shutdownAt;
    static volatile String pendingAction;
    static final Map<String, Long> alerted = new HashMap<>();

    static void run() throws Exception {
        JSONObject c = conf();
        byte[] key = Link.unb64(c.getString("key"));
        String server = c.getString("server"), from = c.getString("from"), to = c.getString("to");
        // heartbeat + local checks
        Thread beat = new Thread(() -> {
            long lastBeat = 0;
            while (true) {
                try {
                    JSONObject s = status();
                    if (System.currentTimeMillis() - lastBeat > 30 * 60_000L) {
                        send(server, to, key, Link.msg("pc_status").put("status", s).put("reason", "heartbeat"));
                        lastBeat = System.currentTimeMillis();
                    }
                    for (String alert : alerts(s)) {
                        Long last = alerted.get(alert.replaceAll("\\d", ""));
                        if (last != null && System.currentTimeMillis() - last < 6 * 3600_000L) continue;
                        alerted.put(alert.replaceAll("\\d", ""), System.currentTimeMillis());
                        if (alert.startsWith("HANG:")) send(server, to, key, Link.msg("pc_alert").put("kill", alert.substring(5))
                                .put("text", alert.substring(5) + " läuft seit über 30 Minuten auf Volllast – hängt vermutlich.").put("status", s));
                        else send(server, to, key, Link.msg("pc_alert").put("text", alert).put("status", s));
                    }
                    if (shutdownAt > 0 && System.currentTimeMillis() >= shutdownAt) { shutdownAt = 0; execute(pendingAction); }
                } catch (Throwable t) {
                    log("Heartbeat: " + t);
                }
                try { Thread.sleep(shutdownAt > 0 ? 1000 : 5 * 60_000L); } catch (InterruptedException ignored) {}
            }
        }, "beat");
        beat.setDaemon(true);
        beat.start();
        send(server, to, key, Link.msg("pc_status").put("status", status()).put("reason", "start"));
        String since = "30s";
        Link.Seen seen = new Link.Seen();
        while (true) {
            try {
                since = Link.subscribe(server, from, since, new Link.Listener() {
                    @Override public void onOpen() {}
                    @Override public void onMessage(String id, String body) {
                        JSONObject m = Link.open(key, from, body);
                        if (m == null || !seen.accept(m, 10 * 60_000L)) return;
                        try { handle(server, to, key, m); } catch (Throwable t) { log("Nachricht: " + t); }
                    }
                }, () -> true);
            } catch (Throwable t) {
                log("Verbindung: " + t.getMessage());
            }
            Thread.sleep(5000);
        }
    }

    static void handle(String server, String to, byte[] key, JSONObject m) throws Exception {
        String rid = m.optString("rid");
        switch (m.optString("t")) {
            case "pc_status_req":
                send(server, to, key, Link.msg("pc_status").put("status", status()).put("rid", rid).put("reason", "request"));
                break;
            case "pc_action": {
                String action = m.optString("action"), value = m.optString("value");
                String result = action(action, value);
                send(server, to, key, Link.msg("pc_result").put("rid", rid).put("result", result));
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ actions (fixed list)

    static String action(String action, String value) throws Exception {
        switch (action) {
            case "shutdown": case "reboot": case "suspend": {
                int delay = 60;
                String what = action.equals("shutdown") ? "herunterfahren" : action.equals("reboot") ? "neu starten" : "in den Ruhezustand schicken";
                notify("Jarvis", "Ich werde den PC in " + delay + " Sekunden " + what + ". Abbrechen: Jarvis „abbrechen“ sagen.");
                pendingAction = action;
                shutdownAt = System.currentTimeMillis() + delay * 1000L;
                return "PC wird in " + delay + " s " + what + " (Hinweis auf dem Bildschirm, abbrechbar).";
            }
            case "cancel":
                if (shutdownAt == 0) return "Es war nichts geplant.";
                shutdownAt = 0;
                notify("Jarvis", "Abgebrochen – der PC bleibt an.");
                return "Abgebrochen, der PC bleibt an.";
            case "lock":
                sh(10, "loginctl", "lock-session");
                return "Bildschirm gesperrt.";
            case "notify":
                notify("Jarvis", value.length() > 300 ? value.substring(0, 300) : value);
                return "Meldung angezeigt.";
            case "volume": {
                int v = Math.max(0, Math.min(100, Integer.parseInt(value.replaceAll("[^0-9]", ""))));
                sh(10, "wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", (v / 100.0) + "");
                return "Lautstärke " + v + " %.";
            }
            case "kill": {
                String name = value.trim();
                if (!name.matches("[A-Za-z0-9._+-]{2,40}")) return "Ungültiger Programmname.";
                if (PROTECTED.contains(name.toLowerCase())) return name + " gehört zum System – das beende ich nicht.";
                String user = System.getProperty("user.name");
                String r = sh(10, "pkill", "-u", user, "-x", name);
                hot.remove(name);
                return sh(5, "pgrep", "-u", user, "-x", name).isBlank() ? name + " beendet." : name + " läuft noch (" + r.trim() + ").";
            }
            case "mute":
                sh(10, "wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle");
                return "Ton umgeschaltet (stumm/an).";
            default:
                return "Unbekannte Aktion – erlaubt: shutdown, reboot, suspend, cancel, lock, notify, volume, mute, kill.";
        }
    }

    /** Never ended through Jarvis: desktop, session, system and Jarvis itself. */
    static final java.util.Set<String> PROTECTED = new java.util.HashSet<>(java.util.Arrays.asList("hyprland", "systemd", "dbus-daemon", "dbus-broker",
            "pipewire", "wireplumber", "pipewire-pulse", "xdg-desktop-portal", "xdg-desktop-portal-hyprland", "sddm", "gdm", "login", "bash", "zsh", "fish",
            "sshd", "networkmanager", "java", "waybar", "hypridle", "hyprlock", "polkit", "kwin_wayland", "plasmashell", "gnome-shell", "xorg", "xwayland"));

    /** Processes seen at full load, consecutive 5-minute checks. */
    static final Map<String, Integer> hot = new HashMap<>();

    static void execute(String action) {
        try {
            if ("shutdown".equals(action)) sh(20, "systemctl", "poweroff");
            else if ("reboot".equals(action)) sh(20, "systemctl", "reboot");
            else if ("suspend".equals(action)) sh(20, "systemctl", "suspend");
        } catch (Exception e) {
            log("Aktion " + action + ": " + e);
        }
    }

    static void notify(String title, String text) {
        try { sh(10, "notify-send", "-a", "Jarvis", "-u", "critical", title, text); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ status

    static JSONObject status() throws Exception {
        JSONObject s = new JSONObject();
        s.put("host", host()).put("agent", VERSION).put("time", System.currentTimeMillis());
        String[] up = read("/proc/uptime").split("\\s+");
        s.put("uptime_min", (long) Double.parseDouble(up[0]) / 60);
        String[] load = read("/proc/loadavg").split("\\s+");
        s.put("load1", Double.parseDouble(load[0])).put("cpus", Runtime.getRuntime().availableProcessors());
        Map<String, Long> mem = new HashMap<>();
        for (String l : read("/proc/meminfo").split("\n")) {
            String[] p = l.split(":\\s+");
            if (p.length == 2) mem.put(p[0], Long.parseLong(p[1].replaceAll("[^0-9]", "")));
        }
        long total = mem.getOrDefault("MemTotal", 1L), avail = mem.getOrDefault("MemAvailable", 0L);
        s.put("ram_used_pct", Math.round(100.0 * (total - avail) / total)).put("ram_total_gb", Math.round(total / 1048576.0));
        JSONArray disks = new JSONArray();
        for (String path : new String[]{"/", System.getProperty("user.home")}) {
            File f = new File(path);
            long t = f.getTotalSpace(), free = f.getUsableSpace();
            if (t > 0) disks.put(new JSONObject().put("path", path).put("used_pct", Math.round(100.0 * (t - free) / t)).put("free_gb", free / 1_073_741_824L));
        }
        s.put("disks", disks);
        double maxTemp = 0;
        File[] zones = new File("/sys/class/thermal").listFiles((d, n) -> n.startsWith("thermal_zone"));
        if (zones != null) for (File z : zones) {
            try { maxTemp = Math.max(maxTemp, Double.parseDouble(read(z + "/temp").trim()) / 1000.0); } catch (Exception ignored) {}
        }
        s.put("temp_max_c", Math.round(maxTemp));
        File[] bats = new File("/sys/class/power_supply").listFiles((d, n) -> n.startsWith("BAT"));
        if (bats != null && bats.length > 0) {
            try { s.put("battery_pct", Integer.parseInt(read(bats[0] + "/capacity").trim())).put("charging", read(bats[0] + "/status").trim()); } catch (Exception ignored) {}
        }
        // the processes using the most CPU right now
        JSONArray top = new JSONArray();
        String ps = sh(5, "ps", "-eo", "comm,pcpu,pmem", "--sort=-pcpu", "--no-headers");
        int n = 0;
        for (String l : ps.split("\n")) {
            if (l.isBlank() || n++ >= 5) continue;
            String[] p = l.trim().split("\\s+");
            if (p.length >= 3) top.put(p[0] + " " + p[1] + "% CPU, " + p[2] + "% RAM");
        }
        s.put("top", top);
        if (updates >= 0) s.put("updates", updates);
        return s;
    }

    /** Pending package updates (slow, once per hour). */
    static long updatesAt;
    static int updates = -1;

    static List<String> alerts(JSONObject s) {
        List<String> out = new ArrayList<>();
        JSONArray d = s.optJSONArray("disks");
        if (d != null) for (int i = 0; i < d.length(); i++) {
            JSONObject x = d.getJSONObject(i);
            if (x.optInt("used_pct") >= 90) out.add("Festplatte " + x.optString("path") + " ist zu " + x.optInt("used_pct") + " % voll (" + x.optLong("free_gb") + " GB frei).");
        }
        if (s.optInt("temp_max_c") >= 90) out.add("Der PC ist sehr heiß: " + s.optInt("temp_max_c") + " °C.");
        if (s.optInt("ram_used_pct") >= 95) out.add("Der Arbeitsspeicher ist zu " + s.optInt("ram_used_pct") + " % belegt.");
        java.util.Set<String> now = new java.util.HashSet<>();
        JSONArray top = s.optJSONArray("top");
        if (top != null) for (int i = 0; i < top.length(); i++) {
            String[] p = top.getString(i).split(" ");
            try { if (Double.parseDouble(p[1].replace("%", "")) >= 90) now.add(p[0]); } catch (Exception ignored) {}
        }
        hot.keySet().retainAll(now);
        for (String n : now) {
            int c = hot.merge(n, 1, Integer::sum);
            if (c == 6 && !PROTECTED.contains(n.toLowerCase())) out.add("HANG:" + n);
        }
        if (System.currentTimeMillis() - updatesAt > 3600_000L) {
            updatesAt = System.currentTimeMillis();
            try {
                String u = sh(120, "checkupdates");
                updates = u.isBlank() ? 0 : u.trim().split("\n").length;
            } catch (Exception ignored) {}
        }
        if (updates >= 0) s.put("updates", updates);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    static void send(String server, String topic, byte[] key, JSONObject m) throws Exception {
        Link.publish(server, topic, Link.seal(key, topic, m));
    }

    static String read(String path) throws Exception { return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8); }

    static String sh(int timeoutSec, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null && b.length() < 20000) b.append(l).append("\n");
        }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly();
        return b.toString();
    }

    /** Arch has no "hostname" binary by default. */
    static String host() {
        try { return read("/etc/hostname").trim(); } catch (Exception e) {
            try { return read("/proc/sys/kernel/hostname").trim(); } catch (Exception e2) { return "PC"; }
        }
    }

    static void log(String s) { System.err.println(new java.util.Date() + " " + s); }
}
