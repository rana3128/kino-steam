'use strict';

const os = require('os');

/**
 * LAN IPv4 addresses this machine can be reached at, best candidate first.
 * Virtual adapters (VirtualBox, WSL, Hyper-V, Docker) sort last because they
 * are almost never the address the TV should use.
 */
function lanAddresses() {
  const ifaces = os.networkInterfaces();
  const found = [];

  for (const [name, addrs] of Object.entries(ifaces)) {
    for (const addr of addrs || []) {
      if (addr.family !== 'IPv4' || addr.internal) continue;
      found.push({ name, address: addr.address, netmask: addr.netmask });
    }
  }

  const isVirtual = (name) => /virtual|vmware|vbox|hyper-v|wsl|docker|loopback|tailscale|zerotier/i.test(name);
  const isPrivate = (ip) =>
    ip.startsWith('192.168.') ||
    ip.startsWith('10.') ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(ip);

  return found.sort((a, b) => {
    if (isVirtual(a.name) !== isVirtual(b.name)) return isVirtual(a.name) ? 1 : -1;
    if (isPrivate(a.address) !== isPrivate(b.address)) return isPrivate(a.address) ? -1 : 1;
    // 192.168.x is the overwhelmingly common home-router range.
    const score = (ip) => (ip.startsWith('192.168.') ? 0 : 1);
    return score(a.address) - score(b.address);
  });
}

function primaryAddress() {
  const list = lanAddresses();
  return list.length ? list[0].address : '127.0.0.1';
}

/** Directed broadcast addresses, so we can reach the LAN even where 255.255.255.255 is dropped. */
function broadcastAddresses() {
  const out = new Set(['255.255.255.255']);

  for (const { address, netmask } of lanAddresses()) {
    if (!netmask) continue;
    const ip = address.split('.').map(Number);
    const mask = netmask.split('.').map(Number);
    if (ip.length !== 4 || mask.length !== 4) continue;
    const bcast = ip.map((octet, i) => (octet & mask[i]) | (~mask[i] & 0xff));
    out.add(bcast.join('.'));
  }

  return [...out];
}

module.exports = { lanAddresses, primaryAddress, broadcastAddresses };
