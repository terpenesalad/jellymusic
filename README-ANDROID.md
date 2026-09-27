# Music Archive — Android app

A native wrapper around the Music Archive web app. It exists for one reason:
a browser tab is not allowed to keep playing music reliably when your phone is
locked, and an app is.

The APK is built by GitHub Actions and published to Releases. You download it
on your phone and install it.

---

## What the wrapper actually adds

The app loads your live site, so this isn't a copy of the web app — it's the
same `index.html`, wrapped in the things only a real app can do:

| | In Chrome | In the app |
|---|---|---|
| Playback with the screen off | Throttled or suspended by the OS | Foreground service keeps the process alive |
| Lock screen / notification controls | None | Full media notification with artwork and a scrubber |
| Bluetooth, headset, car controls | None | Wired through the media session |
| Headphones unplugged | Keeps blasting | Pauses |
| Phone call or navigation prompt | Fights for audio | Proper audio focus — ducks, then resumes |
| `http://` Jellyfin on your LAN | Blocked as mixed content | Allowed |
| Keeping the screen on while playing | Wake Lock API, unreliable | Native, honoured |

**`index.html` needed no changes for any of this.** The bridge adapts the page
from the outside by watching the audio element and intercepting the
`navigator.mediaSession` calls the app already makes. The site keeps working
exactly as it does now in a browser.

---

## Where the files go

Drop these into your existing `jellymusic` repo, next to `index.html`:

```
jellymusic/
├── index.html                    ← already there
├── sw.js  manifest.webmanifest   ← already there
├── android/                      ← new
└── .github/workflows/android.yml ← new
```

Then:

```bash
git add android .github
git commit -m "Add Android app wrapper and APK build"
git push
```

---

## Setting up signing (do this once)

Android identifies an app by its signature. If the signature changes between
builds, the phone treats the new APK as a different app and refuses to install
it over the old one — you'd have to uninstall first every single time, losing
your downloads and settings.

So the signing key needs to be the same on every build, which means storing it
as a repository secret. A keystore has been generated for you.

1. Go to **Settings → Secrets and variables → Actions** in your repo
2. Add four **repository secrets**:

| Name | Value |
|---|---|
| `KEYSTORE_BASE64` | the contents of `keystore.base64.txt` |
| `KEYSTORE_PASSWORD` | the password in `KEYSTORE-PASSWORD.txt` |
| `KEY_ALIAS` | `musicarchive` |
| `KEY_PASSWORD` | the same password |

3. Keep `release.jks` and the password somewhere safe — a password manager is
   ideal. If you lose them you can still build, but you'll have to uninstall
   the app and reinstall to update it, once.

**Don't commit `release.jks` to the repo.** It's a public repo, and anyone with
the keystore could sign an APK that your phone would accept as a legitimate
update to this app.

If you skip this, builds still work — the workflow generates a throwaway key
and says so in the release notes. You just can't update in place.

---

## Building an APK

**Tag a release:**

```bash
git tag v1.0.0
git push --tags
```

**Or** use the Actions tab → *Build APK* → *Run workflow*.

Either way you get a GitHub Release with `MusicArchive-1.0.0.apk` attached.
Open that link on your phone, download, tap it. Android will ask permission to
install from your browser the first time — expected for anything that doesn't
come from the Play Store.

---

## What needs a rebuild, and what doesn't

The app loads `https://terpenesalad.github.io/jellymusic/` at runtime, so:

- **Changes to `index.html`** — no rebuild. Push, and the app picks them up.
- **Changes under `android/`** — rebuild and reinstall.

---

## Moving your data over from Chrome

The app has its own storage, separate from Chrome's, so your bangers and play
history don't come across on their own. **Settings → Backup & transfer** moves
them.

**On your phone, in Chrome** (the browser where all your history is):

1. Open the site as normal
2. Avatar → **Settings → Backup & transfer**
3. Tap **Export backup** — it lands in your Downloads
4. Tick *Include server sign-in* first if you'd rather not retype your Jellyfin
   details in the app. The file then holds an access token, so keep it to
   yourself.

**Then in the app:**

1. Avatar → **Settings → Backup & transfer**
2. **Choose backup file** → pick the file from Downloads
3. It reports what it brought in

What moves: bangers (with their album info), play counts and listening history,
hidden albums, radio stations *and their logos*, album requests, recent
searches, and your view preferences.

What doesn't: downloaded offline tracks — those are gigabytes of audio, so
re-download them in the app.

**Importing merges, it never replaces.** Anything already on the device is
kept, and where both sides know a play count the higher one wins. Running it
twice is harmless. Worth keeping a backup file around regardless — it's also
your insurance against clearing site data.

## Two things worth knowing

**Developer verification.** Google's new rule requires apps to come from a
verified developer, but as of September 2026 it's enforced only for installs
through participating app stores, and only in Brazil, Indonesia, Singapore and
Thailand. Direct APK installs like this one aren't affected, and Australia
isn't in scope. Global expansion is on the roadmap for 2027 and direct
sideloading is expected to keep working through the "advanced flow"; if it ever
does affect you, a free limited-distribution account covers up to 20 devices
without ID or a fee.

---

## Changing the URL

One line in `android/gradle.properties`:

```properties
appUrl=https://terpenesalad.github.io/jellymusic/
```

---

## How it's put together

```
android/app/src/main/
├── java/io/github/terpenesalad/jellymusic/
│   ├── MainActivity.java     WebView host, insets, dialogs, file picker
│   ├── PlaybackService.java  foreground service, media session, notification
│   ├── WebAppBridge.java     the surface JavaScript calls into
│   └── NowPlaying.java       shared now-playing state
└── assets/bridge.js          injected into the page; adapts it to the above
```

Two details that matter if you ever edit this:

- **Never call `web.onPause()` or `pauseTimers()`** in the activity's lifecycle
  callbacks. It's the standard WebView-wrapper reflex and it's exactly what
  kills audio when the screen turns off.
- **Never `finish()` on back.** Destroying the activity destroys the WebView,
  and the WebView *is* the player. Back drops the task to the background
  instead.

### Testing

`tools/test/bridge.test.js` runs the bridge against the real `index.html` in a
simulated WebView — checking that the muted pre-buffer element is ignored, that
a track change doesn't flash "paused" on the lock screen, that notification
buttons reach the app's own handlers, and that metadata still works if the
Media Session API is unavailable.

```bash
cd tools/test && npm install jsdom
node bridge.test.js                      # with the Media Session API
MA_NO_MEDIASESSION=1 node bridge.test.js # fallback path
```
