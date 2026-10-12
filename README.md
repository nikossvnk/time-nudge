# Get Going

A countdown that nudges you as time runs out, built for an old Android phone (7.1+) that stays at home.

## What it does
- Enter minutes (or tap 5/10/15/20/30), tap a preset, or pick **Until a time…**.
- Full-screen countdown with a shrinking wedge, green → amber → red. Screen stays on.
- Spoken alerts that get denser toward the end, with more beeps and louder volume each time.
  For 10 minutes: 3, 6, 8, 9 and 9.5 minutes in, then the alarm at 10.
- At zero: "Time's up!" and a looping alarm. Past zero it keeps counting (+1:30)
  and says "N minutes over" every minute. The alarm sound stops by itself after 2 minutes over,
  voice reminders after 15, and the run auto-closes after 30 (logged with a "?").
- Tap **Done ✓** when you finish: it logs planned vs actual time. The home screen shows your
  track record, e.g. "You usually need about 1.4× the time you plan".

## Build
No Gradle needed. On Ubuntu: `apt install android-sdk-platform-23 aapt apksigner dalvik-exchange zipalign`,
then `./build.sh`. Output: `build/GetGoing.apk`. Keep `keystore/getgoing.jks` so updates install over
the old version without losing presets and history.

`test/` holds a Robolectric smoke test (Maven + JDK 8) that runs a full 10-minute timer in simulated time.
