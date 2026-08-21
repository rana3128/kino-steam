'use strict';

const { app, BrowserWindow, dialog, shell } = require('electron');
const path = require('path');

const { Config } = require('../server/config');
const { VideoServer } = require('../server/httpServer');
const { DiscoveryService } = require('../server/discovery');
const { createTray } = require('./tray');
const { registerIpc } = require('./ipc');

// Second launch should surface the existing window, not start a second server
// that would fail to bind the port.
if (!app.requestSingleInstanceLock()) {
  app.quit();
  process.exit(0);
}

const state = {
  config: null,
  server: null,
  discovery: null,
  window: null,
  tray: null,
  quitting: false,
  status: { running: false, port: null, error: null },
};

function createWindow() {
  const win = new BrowserWindow({
    width: 860,
    height: 660,
    minWidth: 620,
    minHeight: 480,
    title: 'Kino Server',
    backgroundColor: '#12141a',
    autoHideMenuBar: true,
    icon: path.join(__dirname, '..', '..', 'assets', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));

  // Closing hides to tray; the server keeps serving. Quit is explicit.
  win.on('close', (event) => {
    if (state.quitting) return;
    event.preventDefault();
    win.hide();
  });

  // Any external link opens in the real browser, never in an app window.
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  state.window = win;
  return win;
}

function pushStatus() {
  if (state.window && !state.window.isDestroyed()) {
    state.window.webContents.send('status', state.status);
  }
}

async function startServer() {
  await stopServer();

  const folders = state.config.existingFolders();
  const server = new VideoServer({
    port: state.config.get('port'),
    name: state.config.get('name'),
  });
  server.setRoots(folders);
  server.library.onChange = () => pushStatus();

  try {
    const port = await server.listen();
    state.server = server;
    state.status = { running: true, port, error: null };

    const discovery = new DiscoveryService({ httpPort: port, name: state.config.get('name') });
    try {
      await discovery.start();
      state.discovery = discovery;
    } catch (err) {
      // Discovery is optional — manual IP entry on the TV still works.
      console.error('[discovery] unavailable:', err.message);
      state.status.discoveryError = err.message;
    }
  } catch (err) {
    const message = err.code === 'EADDRINUSE'
      ? `Port ${state.config.get('port')} is already in use. Choose another port.`
      : err.message;
    state.status = { running: false, port: null, error: message };
  }

  pushStatus();
  if (state.tray) state.tray.refresh();
  return state.status;
}

async function stopServer() {
  if (state.discovery) {
    state.discovery.stop();
    state.discovery = null;
  }
  if (state.server) {
    await state.server.close();
    state.server = null;
  }
  state.status = { running: false, port: null, error: null };
}

async function pickFolders() {
  const result = await dialog.showOpenDialog(state.window, {
    title: 'Choose folders to share with your TV',
    properties: ['openDirectory', 'multiSelections', 'dontAddToRecent'],
  });
  if (result.canceled || !result.filePaths.length) return 0;

  const added = state.config.addFolders(result.filePaths);
  if (added) await startServer();
  return added;
}

app.on('second-instance', () => {
  if (state.window) {
    if (state.window.isMinimized()) state.window.restore();
    state.window.show();
    state.window.focus();
  }
});

app.whenReady().then(async () => {
  state.config = new Config(app.getPath('userData'));

  createWindow();
  registerIpc({ state, startServer, stopServer, pickFolders });
  state.tray = createTray({
    state,
    onShow: () => {
      state.window.show();
      state.window.focus();
    },
    onAddFolder: pickFolders,
    onQuit: () => {
      state.quitting = true;
      app.quit();
    },
  });

  await startServer();
});

// The tray keeps the app alive with no windows open — that is the point.
app.on('window-all-closed', () => { /* stay resident */ });

app.on('before-quit', () => {
  state.quitting = true;
});

app.on('will-quit', async (event) => {
  if (!state.server && !state.discovery) return;
  event.preventDefault();
  await stopServer();
  app.quit();
});
