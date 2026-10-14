# Time Nudge

A countdown that nudges you as time runs out, for people who lose track of time. It was built for an
old Android phone (7.1+) that stays at home: tell it how long you have, and it keeps you honest.

## What it does
- Enter minutes (or tap 5/10/15/20/30), tap a preset, or pick **Until a time…**.
- Full-screen countdown with a shrinking wedge, green → amber → red. Screen stays on.
- Spoken alerts at least every 5 minutes, denser toward the end, with more beeps and louder volume
  each time. For 10 minutes: 3, 6, 8, 9 and 9.5 minutes in, then the alarm at 10. Timers over
  15 minutes announce every 5 minutes left, then 2 min, 1 min and 30 s.
- At zero: "Time's up!" and a looping alarm. Past zero it keeps counting (+1:30) and says
  "N minutes over" every minute up to 10, then every 5 minutes. The alarm sound stops by itself
  after 2 minutes over, and the run auto-closes after 30 (logged with a "?").
- The notification shows the time left (or over), the end time and a **Done** button.
- If Android kills the app mid-run, the timer comes back where it was (it's saved, and an alarm
  wakes it for the next announcement), and your alarm volume is still restored at the end.
- Tap **Done ✓** when you finish: it logs planned vs actual time. The home screen shows your
  track record, e.g. "You usually need about 1.4× the time you plan". Runs that auto-ended
  (nobody tapped Done) are listed with a "?" but left out of the averages.
- Presets show how long they usually take you (median of the last 10 runs, after 3). Long-press
  one to switch it to that time; swipe one left to delete it (Undo shows for 8 seconds).
- History shows the last 5 runs by day; **Show all** lists everything kept (the last 100).

## Build
No Gradle needed. On Ubuntu:

```sh
sudo apt install android-sdk-platform-23 aapt apksigner dalvik-exchange zipalign
./build.sh            # → build/TimeNudge.apk
```

`build.sh` signs with `keystore/timenudge.jks` (gitignored), or with `KEYSTORE=/path/to/key.jks`, and
stops if the key isn't there. Keep the key: updates signed with the same key install over the old
version without losing presets and history. To create one the first time:

```sh
mkdir -p keystore && keytool -genkeypair -keystore keystore/timenudge.jks -storepass timenudge \
  -keypass timenudge -alias timenudge -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Time Nudge"
```

GitHub Actions builds `TimeNudge-<version>.apk` on every push (download it from the run's artifacts) and attaches it to
a release when you push a `v*` tag. It signs with the repository secret `KEYSTORE_BASE64`
(`base64 -w0 timenudge.jks | gh secret set KEYSTORE_BASE64`).

## Test
`test/` holds a Robolectric smoke test (Maven + JDK 8) that runs a full 10-minute timer in simulated time.

## License
GPL-3.0-or-later. See [LICENSE](LICENSE).
