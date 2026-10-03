/* Music Archive — Windows app.
 *
 * Same idea as the Android wrapper: this loads the live site, so every change
 * to index.html reaches the desktop app without a reinstall. What the shell
 * adds is the stuff a browser tab can't promise:
 *
 *   - Your sign-in survives quitting, restarting and updating. The session
 *     lives in a fixed profile folder and is flushed to disk on every close,
 *     and your password is kept (encrypted by Windows) so a revoked or expired
 *     token is renewed silently instead of throwing you back to the login.
 *   - Playback keeps going when minimised, hidden or with the screen locked
 *     (no background throttling, and Windows is told not to suspend the app).
 *   - Keyboard media keys, Bluetooth headset buttons and the Windows volume
 *     flyout's media controls all work, through the page's existing
 *     navigator.mediaSession code.
 *   - An http:// Jellyfin on your LAN or Tailscale works from the https app.
 */
'use strict';

const {
  app, BrowserWindow, Menu, ipcMain, safeStorage, session, shell,
  powerSaveBlocker, screen,
} = require('electron');
const fs = require('fs');
const path = require('path');

const APP_URL = 'https://terpenesalad.github.io/jellymusic/';
const APP_ORIGIN = new URL(APP_URL).origin;
const APP_ID = 'io.github.terpenesalad.jellymusic';

// A fixed profile folder, so the sign-in and library cache can't be orphaned
// by a rename, and the installer and portable .exe share one session.
app.setPath('userData', path.join(app.getPath('appData'), 'Music Archive'));
app.setAppUserModelId(APP_ID);

// ---------------------------------------------------------------- Chromium
// Music may start from a restored queue without a click.
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
// The app is served over https but most home Jellyfin servers are plain
// http on a private address. A browser treats that as mixed content and
// "local network" access and either upgrades it to https (which then fails)
// or blocks it. This is your own server, so let it through — the Android app
// does the same thing.
app.commandLine.appendSwitch('allow-running-insecure-content');
app.commandLine.appendSwitch('disable-features', [
  'LocalNetworkAccessChecks',
  'LocalNetworkAccessChecksWebSockets',
  'PrivateNetworkAccessSendPreflights',
  'PrivateNetworkAccessRespectPreflightResults',
  'BlockInsecurePrivateNetworkRequests',
  'CalculateNativeWinOcclusion', // a covered window must not be treated as hidden
].join(','));
// Chromium's own media-key handling routes keys to the page's mediaSession.
app.commandLine.appendSwitch('enable-features', 'HardwareMediaKeyHandling');

// ---------------------------------------------------------------- one copy
// A second launch focuses the first window instead of starting a second
// player that would fight it for the session.
if (!app.requestSingleInstanceLock()) {
  app.quit();
  process.exit(0);
}

let win = null;
let quitting = false;

// ---------------------------------------------------------------- files
const userFile = (name) => path.join(app.getPath('userData'), name);

function readJson(name, fallback) {
  try { return JSON.parse(fs.readFileSync(userFile(name), 'utf8')); }
  catch (_) { return fallback; }
}
// Write-then-rename, so a crash or power cut mid-write can never leave a
// half-written file behind (which would read as "no saved login").
function writeAtomic(name, data) {
  const target = userFile(name);
  const tmp = target + '.tmp';
  try {
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(tmp, data);
    fs.renameSync(tmp, target);
  } catch (e) { console.error('write failed', name, e); }
}

// ---------------------------------------------------------------- window size
function loadBounds() {
  const b = readJson('window.json', null);
  if (!b || !b.width || !b.height) return { width: 1200, height: 800 };
  // Only reuse the position if it's still on a connected monitor; otherwise
  // unplugging a second screen would open the app somewhere invisible.
  const onScreen = screen.getAllDisplays().some((d) => {
    const a = d.workArea;
    return b.x >= a.x - 50 && b.y >= a.y - 50 &&
      b.x < a.x + a.width - 100 && b.y < a.y + a.height - 100;
  });
  return onScreen ? b : { width: b.width, height: b.height, maximized: b.maximized };
}
function saveBounds() {
  if (!win || win.isDestroyed()) return;
  const n = win.getNormalBounds();
  writeAtomic('window.json', JSON.stringify({ ...n, maximized: win.isMaximized() }));
}

// ---------------------------------------------------------------- saved login
// The password is encrypted with Windows DPAPI via safeStorage, so it's tied
// to your Windows account: the file is useless if copied to another PC or
// read by another user.
const CREDS = 'credentials.bin';

ipcMain.handle('creds:save', (e, c) => {
  if (!fromApp(e)) return false;
  if (!c || typeof c.url !== 'string' || typeof c.user !== 'string' || typeof c.pass !== 'string') return false;
  if (!safeStorage.isEncryptionAvailable()) return false;
  const blob = safeStorage.encryptString(JSON.stringify({ url: c.url, user: c.user, pass: c.pass }));
  writeAtomic(CREDS, blob);
  flushStorage();
  return true;
});
ipcMain.handle('creds:load', (e) => {
  if (!fromApp(e)) return null;
  try {
    if (!safeStorage.isEncryptionAvailable()) return null;
    return JSON.parse(safeStorage.decryptString(fs.readFileSync(userFile(CREDS))));
  } catch (_) { return null; }
});
ipcMain.handle('creds:clear', (e) => {
  if (!fromApp(e)) return false;
  try { fs.unlinkSync(userFile(CREDS)); } catch (_) {}
  flushStorage();
  return true;
});
// Lets the page force everything to disk right after it saves the session.
ipcMain.handle('storage:flush', (e) => { if (fromApp(e)) flushStorage(); return true; });

function fromApp(e) {
  try { return new URL(e.senderFrame.url).origin === APP_ORIGIN; }
  catch (_) { return false; }
}

// localStorage (where the sign-in token lives) is written to disk lazily.
// Forcing it at the moments that matter is what makes "quit and reopen"
// reliably come back signed in, even if the app is killed rather than closed.
function flushStorage() {
  try { session.defaultSession.flushStorageData(); } catch (_) {}
}

// ---------------------------------------------------------------- keep awake
// While music plays, stop Windows from suspending the app (it never stops the
// screen turning off or the PC locking — just keeps the music going).
let blockerId = null;
function setPlaying(on) {
  if (on && blockerId === null) {
    blockerId = powerSaveBlocker.start('prevent-app-suspension');
  } else if (!on && blockerId !== null) {
    try { powerSaveBlocker.stop(blockerId); } catch (_) {}
    blockerId = null;
  }
}

// ---------------------------------------------------------------- offline
// If the site can't be reached at launch (no internet, GitHub having a
// moment) show a quiet page that keeps retrying, rather than a blank window.
function offlinePage() {
  const html = `<!doctype html><meta charset="utf-8"><title>Music Archive</title>
<style>html,body{height:100%;margin:0;background:#0a0a0a;color:#aaa;
font:15px system-ui,-apple-system,Segoe UI,sans-serif;display:flex;align-items:center;
justify-content:center;text-align:center}b{color:#eee;font-weight:600}</style>
<div><b>Can't reach Music Archive</b><br><br>Retrying automatically…</div>`;
  return 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
}
let retryTimer = null;
function scheduleRetry() {
  clearTimeout(retryTimer);
  retryTimer = setTimeout(() => {
    if (win && !win.isDestroyed()) win.loadURL(APP_URL).catch(() => {});
  }, 5000);
}

// ---------------------------------------------------------------- window
function createWindow() {
  const b = loadBounds();
  win = new BrowserWindow({
    width: b.width, height: b.height, x: b.x, y: b.y,
    minWidth: 360, minHeight: 560,
    backgroundColor: '#0A0A0A',
    title: 'Music Archive',
    icon: path.join(__dirname, 'icon.png'),
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      // Keep timers and the next-track handoff running at full speed when
      // the window is minimised or covered — this is what stops the queue
      // stalling between songs in the background.
      backgroundThrottling: false,
      allowRunningInsecureContent: true,
      spellcheck: false,
    },
  });
  if (b.maximized) win.maximize();
  win.once('ready-to-show', () => win.show());

  const wc = win.webContents;

  // Allow the page anything it asks for (notifications, media keys, local
  // network) — it's your own app. Anything else gets nothing.
  const ses = wc.session;
  ses.setPermissionRequestHandler((_wc, _perm, cb, details) => {
    try { cb(new URL(details.requestingUrl).origin === APP_ORIGIN); } catch (_) { cb(false); }
  });
  ses.setPermissionCheckHandler((_wc, _perm, origin) => {
    try { return new URL(origin).origin === APP_ORIGIN; } catch (_) { return false; }
  });

  // Links to anywhere else open in your normal browser.
  wc.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  wc.on('will-navigate', (e, url) => {
    if (url.startsWith('data:')) return;
    try { if (new URL(url).origin === APP_ORIGIN) return; } catch (_) {}
    e.preventDefault();
    if (/^https?:/i.test(url)) shell.openExternal(url);
  });

  // The page asks "Leave site? Music is playing" on unload. In a browser
  // that's a dialog; in an app it would silently block closing and reloads,
  // so the window would just refuse to close. Always let it through.
  wc.on('will-prevent-unload', (e) => e.preventDefault());

  wc.on('did-fail-load', (_e, code, desc, url, isMainFrame) => {
    if (!isMainFrame || code === -3 /* aborted, e.g. a redirect */) return;
    console.warn('load failed', code, desc, url);
    if (!String(url).startsWith('data:')) wc.loadURL(offlinePage()).catch(() => {});
    scheduleRetry();
  });

  // If the page crashes or is killed for memory, bring it straight back. The
  // queue and position are saved, so it resumes where it was.
  wc.on('render-process-gone', (_e, d) => {
    console.warn('renderer gone', d.reason);
    setPlaying(false);
    if (quitting || d.reason === 'clean-exit') return;
    setTimeout(() => { if (win && !win.isDestroyed()) win.loadURL(APP_URL).catch(scheduleRetry); }, 1000);
  });

  wc.on('media-started-playing', () => setPlaying(true));
  wc.on('media-paused', () => setPlaying(false));

  wc.on('page-title-updated', (e) => { e.preventDefault(); });

  // Handy keys: F5/Ctrl+R reload, F11 full screen, Ctrl+Shift+I dev tools,
  // Ctrl +/-/0 zoom. Space etc. stay with the page.
  wc.on('before-input-event', (e, input) => {
    if (input.type !== 'keyDown') return;
    const ctrl = input.control || input.meta;
    const k = input.key;
    if (k === 'F5' || (ctrl && k.toLowerCase() === 'r')) { wc.reload(); e.preventDefault(); }
    else if (k === 'F11') { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    else if (ctrl && input.shift && k.toLowerCase() === 'i') { wc.toggleDevTools(); e.preventDefault(); }
    else if (ctrl && (k === '=' || k === '+')) { wc.setZoomLevel(wc.getZoomLevel() + 0.5); e.preventDefault(); }
    else if (ctrl && k === '-') { wc.setZoomLevel(wc.getZoomLevel() - 0.5); e.preventDefault(); }
    else if (ctrl && k === '0') { wc.setZoomLevel(0); e.preventDefault(); }
  });

  // Save to disk whenever the window loses focus or is minimised — the
  // moments just before people tend to quit or shut down.
  win.on('blur', flushStorage);
  win.on('minimize', flushStorage);
  win.on('resized', saveBounds);
  win.on('moved', saveBounds);
  win.on('close', () => { saveBounds(); flushStorage(); });
  win.on('closed', () => { win = null; });

  win.loadURL(APP_URL).catch(() => {});
}

// Belt and braces for the session: also flush every minute, so even a hard
// power-off loses at most a minute of state (the login itself is flushed the
// moment it's saved).
setInterval(flushStorage, 60_000);

app.on('second-instance', () => {
  if (!win) return createWindow();
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
});

app.on('before-quit', () => { quitting = true; flushStorage(); });
app.on('window-all-closed', () => { setPlaying(false); flushStorage(); app.quit(); });

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  createWindow();
});
