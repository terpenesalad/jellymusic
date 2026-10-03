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

## Building it yourself

```bash
cd desktop
npm ci
npm start                # run it
npm run dist             # build the .exe files into desktop/dist
```
