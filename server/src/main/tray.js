'use strict';

const { Tray, Menu, nativeImage } = require('electron');
const path = require('path');

const { lanAddresses } = require('../server/netInfo');

function trayIcon() {
  const file = path.join(__dirname, '..', '..', 'assets', 'tray.png');
  const image = nativeImage.createFromPath(file);
  // A missing/unreadable icon yields an empty image and an invisible tray item;
  // fall back to a drawn placeholder so the app is never unreachable.
  if (image.isEmpty()) {
    return nativeImage.createFromDataURL(
      'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAQklEQVR42mNk'
      + 'YPjPgAwYGRn/M9IcMDIy/mekOWBkZPzPSHPAyMj4n5HmgJGR8T8jzQEjI+N/RpoDRkbG/4w0BwCk'
      + 'HgQhVFYFsAAAAABJRU5ErkJggg=='
    );
  }
  return image.resize({ width: 16, height: 16 });
}

function createTray({ state, onShow, onAddFolder, onQuit }) {
  const tray = new Tray(trayIcon());

  const build = () => {
    const { running, port } = state.status;
    const addresses = running ? lanAddresses().map((a) => `${a.address}:${port}`) : [];

    const template = [
      {
        label: running ? `Serving on port ${port}` : 'Server stopped',
        enabled: false,
      },
    ];

    if (addresses.length) {
      template.push({ type: 'separator' });
      for (const addr of addresses) {
        template.push({ label: `  ${addr}`, enabled: false });
      }
    }

    template.push(
      { type: 'separator' },
      { label: 'Open Kino Server', click: onShow },
      { label: 'Add folder…', click: onAddFolder },
      { type: 'separator' },
      { label: 'Quit', click: onQuit },
    );

    tray.setContextMenu(Menu.buildFromTemplate(template));
    tray.setToolTip(running ? `Kino Server — port ${port}` : 'Kino Server — stopped');
  };

  build();
  tray.on('click', onShow);
  tray.on('double-click', onShow);

  return { tray, refresh: build };
}

module.exports = { createTray };
