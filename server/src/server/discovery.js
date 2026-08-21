'use strict';

const dgram = require('dgram');
const os = require('os');

const DISCOVERY_PORT = 48200;
const MAGIC = 'KINO_DISCOVER';
const ANNOUNCE_INTERVAL_MS = 5000;

const { broadcastAddresses, primaryAddress } = require('./netInfo');

/**
 * UDP presence so the TV finds the laptop without anyone typing an IP.
 *
 * Two mechanisms, because home routers vary:
 *  - respond to a TV's broadcast probe (works when the TV can broadcast to us)
 *  - announce ourselves every 5s (works when the TV can only receive)
 * Manual IP entry in the TV app covers routers that isolate clients entirely.
 */
class DiscoveryService {
  constructor({ httpPort, name = os.hostname() }) {
    this.httpPort = httpPort;
    this.name = name;
    this.socket = null;
    this.timer = null;
  }

  start() {
    return new Promise((resolve, reject) => {
      const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
      this.socket = socket;

      socket.on('error', (err) => {
        // Discovery is a convenience; never take the HTTP server down with it.
        console.error('[discovery] socket error:', err.message);
        this.stop();
      });

      socket.on('message', (msg, rinfo) => {
        if (!msg.toString('utf8').trim().startsWith(MAGIC)) return;
        this._sendTo(rinfo.address, rinfo.port);
      });

      socket.bind(DISCOVERY_PORT, () => {
        try {
          socket.setBroadcast(true);
        } catch { /* some adapters refuse; announcements just won't go out */ }
        this.timer = setInterval(() => this.announce(), ANNOUNCE_INTERVAL_MS);
        this.announce();
        resolve();
      });

      socket.once('error', reject);
    });
  }

  payload() {
    return Buffer.from(JSON.stringify({
      magic: 'KINO',
      name: this.name,
      host: primaryAddress(),
      port: this.httpPort,
      protocol: 1,
    }));
  }

  _sendTo(address, port) {
    if (!this.socket) return;
    const buf = this.payload();
    this.socket.send(buf, 0, buf.length, port, address, (err) => {
      if (err) console.error('[discovery] reply failed:', err.message);
    });
  }

  announce() {
    if (!this.socket) return;
    const buf = this.payload();
    for (const addr of broadcastAddresses()) {
      this.socket.send(buf, 0, buf.length, DISCOVERY_PORT, addr, () => { /* best effort */ });
    }
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    if (this.socket) {
      try {
        this.socket.close();
      } catch { /* already closed */ }
      this.socket = null;
    }
  }
}

module.exports = { DiscoveryService, DISCOVERY_PORT, MAGIC };
