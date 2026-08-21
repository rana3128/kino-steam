'use strict';

const { ipcMain, app, shell } = require('electron');
const QRCode = require('qrcode');

const { lanAddresses } = require('../server/netInfo');

/**
 * Every channel the renderer can reach. The renderer has no Node access, so
 * this is the entire trust boundary — keep it narrow and validate inputs.
 */
function registerIpc({ state, startServer, stopServer, pickFolders }) {
  ipcMain.handle('get-state', async () => {
    const port = state.status.port || state.config.get('port');
    const existing = new Set(state.config.existingFolders());

    return {
      status: state.status,
      port: state.config.get('port'),
      name: state.config.get('name'),
      startOnLogin: app.getLoginItemSettings().openAtLogin,
      indexed: state.server ? state.server.library.index.size : 0,
      folders: state.config.get('folders').map((path) => ({
        path,
        missing: !existing.has(path),
      })),
      addresses: lanAddresses().map(({ name, address }) => ({
        name,
        address,
        url: `http://${address}:${port}`,
      })),
    };
  });

  ipcMain.handle('pick-folders', () => pickFolders());

  ipcMain.handle('remove-folder', async (event, folderPath) => {
    if (typeof folderPath !== 'string' || !folderPath) return false;
    const removed = state.config.removeFolder(folderPath);
    if (removed) await startServer();
    return removed;
  });

  ipcMain.handle('open-folder', (event, folderPath) => {
    if (typeof folderPath !== 'string' || !folderPath) return false;
    // Only paths we actually have configured — never an arbitrary renderer string.
    if (!state.config.get('folders').includes(folderPath)) return false;
    shell.openPath(folderPath);
    return true;
  });

  ipcMain.handle('set-port', async (event, port) => {
    const n = Number(port);
    if (!Number.isInteger(n) || n < 1024 || n > 65535) {
      return { ok: false, error: 'Port must be a whole number between 1024 and 65535.' };
    }
    state.config.set('port', n);
    const status = await startServer();
    return { ok: status.running, error: status.error };
  });

  ipcMain.handle('set-name', (event, name) => {
    if (typeof name !== 'string') return false;
    const trimmed = name.trim().slice(0, 48);
    if (!trimmed) return false;
    state.config.set('name', trimmed);
    if (state.discovery) state.discovery.name = trimmed;
    if (state.server) state.server.name = trimmed;
    return true;
  });

  ipcMain.handle('set-start-on-login', (event, enabled) => {
    const on = Boolean(enabled);
    app.setLoginItemSettings({ openAtLogin: on, args: ['--hidden'] });
    state.config.set('startOnLogin', on);
    return on;
  });

  ipcMain.handle('restart-server', () => startServer());
  ipcMain.handle('stop-server', () => stopServer());

  ipcMain.handle('rescan', () => {
    if (!state.server) return 0;
    state.server.library.rebuildIndex();
    return state.server.library.index.size;
  });

  ipcMain.handle('qr-code', async (event, url) => {
    if (typeof url !== 'string' || !/^https?:\/\/[\w.:-]+$/.test(url)) return null;
    return QRCode.toDataURL(url, { width: 220, margin: 1, color: { dark: '#000000', light: '#ffffff' } });
  });

  ipcMain.handle('open-external', (event, url) => {
    if (typeof url !== 'string' || !/^https?:\/\//.test(url)) return false;
    shell.openExternal(url);
    return true;
  });
}

module.exports = { registerIpc };
