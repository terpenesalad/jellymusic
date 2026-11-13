# Music Archive — Windows app

The same app as on your phone, as a proper Windows program. Like the Android
app it loads the live site, so changes to `index.html` arrive on their own;
you only download a new `.exe` when the Windows shell itself changes.

## Getting it

Every release in **Releases** now carries two Windows files next to the APK:

| File | What it is |
|---|---|
| `MusicArchive-Setup-x.y.z.exe` | Installer — Start menu + desktop shortcut, one click |
| `MusicArchive-x.y.z-portable.exe` | Runs without installing |

Both use the same sign-in, so you can switch between them freely.

Windows will show **"Windows protected your PC"** the first time, because the
app isn't code-signed (a certificate costs money every year). Click
**More info → Run anyway**. It only asks once per file.

## Staying signed in

You sign in once. After that, quitting, restarting the PC, or installing a
new version all bring you straight back in:

- The session is stored in a fixed folder (`%APPDATA%\Music Archive`) and
  forced to disk the moment you sign in, whenever the window loses focus, and
  on quit — so even killing the app or a power cut doesn't lose it.
- Your password is kept too, encrypted by Windows itself (DPAPI). It can only
  be decrypted by your Windows account on this PC. If Jellyfin ever expires or
  revokes the session, the app quietly signs in again instead of showing the
  login screen.
- If the server isn't up yet when the app opens (PC still booting, Wi‑Fi
  reconnecting), it keeps retrying for about a minute rather than giving up.
- **Sign out** in the app forgets the password as well. Uninstalling keeps it,
  so a reinstall comes back signed in.

## Playback

- Keeps playing and moving to the next song while minimised, behind other
  windows, or with the PC locked — background throttling is off and Windows is
  told not to suspend the app while music is playing.
- Keyboard media keys, headset buttons and the media controls in the Windows
  volume / quick-settings flyout all work.
- An `http://` Jellyfin on your LAN or Tailscale works, same as the Android app.
- If the page ever crashes it reloads itself and picks up your queue.
- Closing the window while music plays just closes it (no "leave site?" prompt).

Shortcuts: **F11** full screen, **Ctrl + / − / 0** zoom, **F5** reload.

## No internet? Use it on the server PC

Install the app on the PC that runs Jellyfin and it keeps working when the
internet goes down — the whole library, artwork, playlists, likes, bangers
and lyrics — because nothing it needs leaves that PC:

- **The app opens offline.** A copy of the site ships inside the app. If
  GitHub can't be reached it opens from that copy, at the same address, so
  you're still signed in with your queue and settings intact. As soon as the
  internet is back you get the latest version again.
- **It talks to Jellyfin at `localhost`.** On the server PC the app checks
  whether the Jellyfin running there is the one you're signed in to, and if
  so uses it directly instead of your Tailscale/LAN address. The sidebar
  shows **● On this PC** while it's doing that. Songs play at original
  quality, since there's no connection to protect.
- **It copes with Jellyfin starting late or stopping.** If Jellyfin starts
  after the app, it switches over within about 15 seconds, mid-song, without
  losing your place. If Jellyfin on the PC is stopped, it goes back to your
  saved address (if that's reachable).
- **The font is bundled too**, so it looks exactly the same offline.

On any other PC nothing changes: there's no Jellyfin on `localhost`, so it
uses your saved address as before. Internet radio stations still need the
internet, of course. Phones can't reach the PC through Tailscale while the
internet is down; downloaded music in the Offline tab still plays.

It looks for Jellyfin on port 8096 (Jellyfin's default), or on the same
port as your saved address if that's different.

## Building it yourself

```bash
cd desktop
npm ci
npm start                # run it (copies the site in first)
npm run dist             # build the .exe files into desktop/dist
```
