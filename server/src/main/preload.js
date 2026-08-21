'use strict';

const { contextBridge, ipcRenderer } = require('electron');

// The renderer sees only these functions — no require, no ipcRenderer, no fs.
contextBridge.exposeInMainWorld('api', {
  getState: () => ipcRenderer.invoke('get-state'),
  pickFolders: () => ipcRenderer.invoke('pick-folders'),
  removeFolder: (path) => ipcRenderer.invoke('remove-folder', path),
  openFolder: (path) => ipcRenderer.invoke('open-folder', path),
  setPort: (port) => ipcRenderer.invoke('set-port', port),
  setName: (name) => ipcRenderer.invoke('set-name', name),
  setStartOnLogin: (enabled) => ipcRenderer.invoke('set-start-on-login', enabled),
  restartServer: () => ipcRenderer.invoke('restart-server'),
  rescan: () => ipcRenderer.invoke('rescan'),
  qrCode: (url) => ipcRenderer.invoke('qr-code', url),
  openExternal: (url) => ipcRenderer.invoke('open-external', url),

  onStatus: (callback) => {
    const handler = (event, status) => callback(status);
    ipcRenderer.on('status', handler);
    return () => ipcRenderer.removeListener('status', handler);
  },
});
