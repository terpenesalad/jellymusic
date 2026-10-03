/* The only thing the page can reach in the desktop shell. index.html checks
 * for window.maDesktop and, when it's there, keeps your login so it can sign
 * back in by itself. In a normal browser this object doesn't exist and the
 * site behaves exactly as before. */
'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('maDesktop', {
  platform: 'windows',
  saveLogin: (url, user, pass) => ipcRenderer.invoke('creds:save', { url, user, pass }),
  loadLogin: () => ipcRenderer.invoke('creds:load'),
  clearLogin: () => ipcRenderer.invoke('creds:clear'),
  flush: () => ipcRenderer.invoke('storage:flush'),
});
