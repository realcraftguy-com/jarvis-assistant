package de.felix.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import org.json.JSONObject;

/**
 * Development hook for measurements over ADB. Only the shell can send it (android.permission.DUMP),
 * and it does nothing in non-debuggable (release) builds.
 *   adb shell am broadcast -a de.felix.jarvis.DEBUG -n de.felix.jarvis/.DebugReceiver --es cmd bench
 */
public class DebugReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        String cmd = i.getStringExtra("cmd");
        String text = i.getStringExtra("text");
        PendingResult pr = goAsync();
        new Thread(() -> {
            String out;
            long t0 = System.currentTimeMillis();
            try {
                switch (cmd == null ? "" : cmd) {
                    case "bench": out = ModelBench.run(c); break;
                    case "selftest": out = SelfTest.run(c); break;
                    case "school": out = Brain.get(c).checkSchool(); break;
                    case "chat": out = Brain.get(c).agent.respond("app", text, null); break;
                    case "call": out = Brain.get(c).agent.respond("call", text, null); break;
                    case "livetest": out = LiveTest.run(c, text == null ? "Was steht diese Woche in der Schule an?" : text); break;
                    case "ttraw": {
                        EduPage ep = new EduPage(c);
                        JSONObject home = ep.userhome();
                        JSONObject tt = ep.timetableRaw(home, text, text);
                        JSONObject dbi = home.optJSONObject("dbi");
                        JSONObject keep = new JSONObject().put("tt", tt).put("periods", dbi == null ? null : dbi.opt("periods"))
                                .put("subjects", dbi == null ? null : dbi.opt("subjects")).put("teachers", dbi == null ? null : dbi.opt("teachers"))
                                .put("classrooms", dbi == null ? null : dbi.opt("classrooms"));
                        java.nio.file.Files.write(new java.io.File(c.getFilesDir(), "tt-raw.json").toPath(), keep.toString().getBytes("UTF-8"));
                        out = "ok " + tt.optJSONArray("ttitems");
                        out = out.length() > 200 ? out.substring(0, 200) : out;
                        break;
                    }
                    case "briefing": out = new Briefing(c).compose(text == null ? "ask" : text, System.currentTimeMillis() - 86_400_000L); break;
                    case "urgentdry": {
                        Db.get(c).kvSet("urgent_dry", "1");
                        Brain.get(c).urgent("dry:" + System.currentTimeMillis(), "Boss, neu in EduPage: Mathe – Leistungsnachweis schon morgen.");
                        out = "vorgemerkt (Probelauf)";
                        break;
                    }
                    case "urgentreal": { Db.get(c).kvSet("urgent_dry", "0"); out = "Probelauf aus"; break; }
                    case "calwatch": {
                        // test mode: the local test calendar counts, "[TEST]" entries count as someone else's
                        Db.get(c).kvSet("calwatch_test", "1".equals(text) ? "1" : "0".equals(text) ? "0" : Db.get(c).kv("calwatch_test", "0"));
                        Db.get(c).kvSet("calwatch_at", 0);
                        new CalendarWatch(c).tick();
                        out = "Schnappschuss " + Db.get(c).kv("calwatch_snap", "").length() + " Zeichen";
                        break;
                    }
                    case "testev": { // add|title|startEpochMs|minutes  or  del|eventId  – only in the local test calendar
                        String[] p = text.split("\\|");
                        android.content.ContentResolver cr = c.getContentResolver();
                        if ("add".equals(p[0])) {
                            android.content.ContentValues v = new android.content.ContentValues();
                            long st = Long.parseLong(p[2]);
                            v.put("calendar_id", new CalendarStore(c).ensureTestCalendar()); v.put("title", "[TEST] " + p[1]);
                            v.put("dtstart", st); v.put("dtend", st + Long.parseLong(p[3]) * 60_000L); v.put("eventTimezone", Cfg.ZONE.getId());
                            out = "id " + android.content.ContentUris.parseId(cr.insert(android.provider.CalendarContract.Events.CONTENT_URI, v));
                        } else {
                            out = "gelöscht " + cr.delete(android.provider.CalendarContract.Events.CONTENT_URI.buildUpon()
                                    .appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                                    .appendQueryParameter("account_name", "Jarvis Test").appendQueryParameter("account_type", android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL).build(),
                                    "_id=? AND calendar_id=?", new String[]{p[1], String.valueOf(new CalendarStore(c).ensureTestCalendar())});
                        }
                        break;
                    }
                    case "edupage": { // save a raw EduPage page for inspection
                        String body = new EduPage(c).page(text);
                        java.nio.file.Files.write(new java.io.File(c.getFilesDir(), "edu-page.html").toPath(), body.getBytes("UTF-8"));
                        out = "gespeichert " + body.length() + " Zeichen";
                        break;
                    }
                    case "persona": { // dump prompts (time removed) to compare builds
                        String all = Agent.personaStatic("telegram") + "\n#####\n" + Agent.personaStatic("call") + "\n#####\n" + Agent.claudeRules()
                                + "\n#####\n" + Brain.get(c).agent.toolsFor("claude").toString(1) + "\n#####\n" + Brain.get(c).agent.toolsFor("call").toString(1)
                                + "\n#####\n" + CallLink.get(c).liveSystem("TESTGRUND").replaceAll("(?s)Jetzt: .*?Uhr[^\n]*", "") + "\n#####\n" + Tools.weather("2026-10-12").replaceAll(":.*", ":");
                        java.nio.file.Files.write(new java.io.File(c.getFilesDir(), "persona.txt").toPath(), all.getBytes("UTF-8"));
                        out = "persona " + all.length();
                        break;
                    }
                    case "pccode": { // pairing code into a private file (read once by the setup script, then deleted)
                        java.nio.file.Files.write(new java.io.File(c.getFilesDir(), "pc-code.txt").toPath(), PcLink.get(c).newPairingCode().getBytes("UTF-8"));
                        out = "Code in Datei";
                        break;
                    }
                    case "maildry": out = new MailWatch(c).check(3); break;
                    case "stt": { // speech recognition of the last voice reply, under Telegram's file name
                        byte[] a = java.nio.file.Files.readAllBytes(new java.io.File(c.getCacheDir(), "jarvis-voice.ogg").toPath());
                        out = new Llm(c).transcribe(a, "file_42.oga");
                        break;
                    }
                    case "voicetest": out = Voice.reply(c, text == null ? "[TEST] Kurzer Stimmtest von Jarvis." : text) ? "gesendet" : "fehlgeschlagen"; break;
                    case "tool": { JSONObject a = new JSONObject(text); out = Brain.get(c).agent.run(a.getString("name"), a.getJSONObject("args")); break; }
                    case "claudehealth": { JSONObject h = new ClaudeBridge(c).health(true); out = h == null ? "nicht erreichbar" : h.toString(); break; }
                    case "sendapp": {
                        java.io.File f = new java.io.File(c.getFilesDir(), "Jarvis-Anruf.apk");
                        try (java.io.InputStream in = c.getAssets().open("jarvis-call.apk"); java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                        }
                        new Telegram(c).sendDocument(f, text == null ? "Update der Jarvis-Anruf-App." : text);
                        out = "gesendet (" + f.length() / 1024 + " KB)";
                        break;
                    }
                    default: out = "unbekannt";
                }
            } catch (Exception e) {
                out = "Fehler: " + e;
            }
            Db.get(c).log("I", "debug", cmd + " (" + (System.currentTimeMillis() - t0) + " ms) → " + out.replace("\n", " | "));
            pr.finish();
        }).start();
    }
}
