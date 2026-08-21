'use strict';

const el = {
  status: document.getElementById('status'),
  statusText: document.querySelector('#status .status-text'),
  error: document.getElementById('error'),
  folders: document.getElementById('folders'),
  indexed: document.getElementById('indexed'),
  addresses: document.getElementById('addresses'),
  qr: document.getElementById('qr'),
  addFolder: document.getElementById('add-folder'),
  name: document.getElementById('name'),
  port: document.getElementById('port'),
  savePort: document.getElementById('save-port'),
  startOnLogin: document.getElementById('start-on-login'),
};

let lastQrUrl = null;

function renderStatus(status) {
  const on = status.running;
  el.status.className = `status ${on ? 'on' : 'off'}`;
  el.statusText.textContent = on ? `Running on port ${status.port}` : 'Stopped';

  if (status.error) {
    el.error.hidden = false;
    el.error.textContent = status.error;
  } else {
    el.error.hidden = true;
  }
}

function renderFolders(folders) {
  el.folders.innerHTML = '';

  if (!folders.length) {
    const li = document.createElement('li');
    li.className = 'empty';
    li.textContent = 'No folders shared yet. Add one to get started.';
    el.folders.append(li);
    return;
  }

  for (const folder of folders) {
    const li = document.createElement('li');
    if (folder.missing) li.className = 'missing';

    const path = document.createElement('span');
    path.className = 'path';
    path.textContent = folder.path;
    path.title = folder.path;
    li.append(path);

    if (folder.missing) {
      const tag = document.createElement('span');
      tag.className = 'tag';
      tag.textContent = 'not found';
      li.append(tag);
    } else {
      const open = document.createElement('button');
      open.className = 'ghost';
      open.textContent = 'Open';
      open.onclick = () => window.api.openFolder(folder.path);
      li.append(open);
    }

    const remove = document.createElement('button');
    remove.className = 'ghost';
    remove.textContent = 'Remove';
    remove.onclick = async () => {
      remove.disabled = true;
      await window.api.removeFolder(folder.path);
      refresh();
    };
    li.append(remove);

    el.folders.append(li);
  }
}

async function renderAddresses(addresses) {
  el.addresses.innerHTML = '';

  if (!addresses.length) {
    const li = document.createElement('li');
    li.className = 'empty';
    li.textContent = 'No network connection detected.';
    el.addresses.append(li);
    el.qr.innerHTML = '';
    lastQrUrl = null;
    return;
  }

  for (const { name, url } of addresses) {
    const li = document.createElement('li');

    const link = document.createElement('a');
    link.className = 'url';
    link.textContent = url;
    link.href = '#';
    link.onclick = (e) => {
      e.preventDefault();
      window.api.openExternal(url);
    };
    li.append(link);

    const iface = document.createElement('span');
    iface.className = 'iface';
    iface.textContent = name;
    li.append(iface);

    el.addresses.append(li);
  }

  // QR for the best-guess LAN address only; regenerating on every poll would flicker.
  const primary = addresses[0].url;
  if (primary !== lastQrUrl) {
    lastQrUrl = primary;
    const dataUrl = await window.api.qrCode(primary);
    el.qr.innerHTML = '';
    if (dataUrl) {
      const img = document.createElement('img');
      img.src = dataUrl;
      img.alt = primary;
      el.qr.append(img);
    }
  }
}

async function refresh() {
  const state = await window.api.getState();

  renderStatus(state.status);
  renderFolders(state.folders);
  await renderAddresses(state.addresses);

  el.indexed.textContent = state.status.running
    ? `${state.indexed} files and folders indexed.`
    : '';

  // Don't clobber what the user is currently typing.
  if (document.activeElement !== el.name) el.name.value = state.name;
  if (document.activeElement !== el.port) el.port.value = state.port;
  el.startOnLogin.checked = state.startOnLogin;
}

el.addFolder.onclick = async () => {
  el.addFolder.disabled = true;
  try {
    await window.api.pickFolders();
    await refresh();
  } finally {
    el.addFolder.disabled = false;
  }
};

el.savePort.onclick = async () => {
  el.savePort.disabled = true;
  const result = await window.api.setPort(el.port.value);
  if (!result.ok && result.error) {
    el.error.hidden = false;
    el.error.textContent = result.error;
  }
  el.savePort.disabled = false;
  lastQrUrl = null; // port changed -> the QR must change with it
  await refresh();
};

el.name.onchange = async () => {
  await window.api.setName(el.name.value);
  await refresh();
};

el.startOnLogin.onchange = async () => {
  await window.api.setStartOnLogin(el.startOnLogin.checked);
};

window.api.onStatus((status) => {
  renderStatus(status);
  refresh();
});

refresh();
// Network interfaces change (Wi-Fi drops, VPN connects) without any IPC event.
setInterval(refresh, 5000);
