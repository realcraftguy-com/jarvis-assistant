#!/bin/sh
# Development helper: run SQL against Jarvis' database on the phone (app is stopped meanwhile).
# usage: tools/dbedit.sh "UPDATE kv SET v='1' WHERE k='x'"
set -e
W=$(mktemp -d)
adb shell am force-stop de.felix.jarvis
for f in jarvis.db jarvis.db-wal jarvis.db-shm; do adb exec-out run-as de.felix.jarvis cat databases/$f > $W/$f 2>/dev/null || true; done
python3 - "$W/jarvis.db" "$1" <<'PY'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1]); c.executescript(sys.argv[2]); c.commit()
c.execute("PRAGMA wal_checkpoint(TRUNCATE)"); c.execute("PRAGMA journal_mode=DELETE"); c.close()
PY
adb shell "run-as de.felix.jarvis rm -f databases/jarvis.db-wal databases/jarvis.db-shm"
adb exec-in run-as de.felix.jarvis sh -c 'cat > databases/jarvis.db' < $W/jarvis.db 2>/dev/null || cat $W/jarvis.db | adb shell "run-as de.felix.jarvis sh -c 'cat > databases/jarvis.db'"
rm -rf $W
adb shell am start -n de.felix.jarvis/.MainActivity >/dev/null
