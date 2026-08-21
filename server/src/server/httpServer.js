'use strict';

const express = require('express');
const os = require('os');
const path = require('path');

const { Library } = require('./library');
const { sendFileRange } = require('./stream');
const { readSubtitle, srtToVtt } = require('./subtitles');
const { lanAddresses } = require('./netInfo');

const VERSION = require('../../package.json').version;

class VideoServer {
  constructor({ port = 8080, name = os.hostname() } = {}) {
    this.port = port;
    this.name = name;
    this.library = new Library();
    this.server = null;
    this.app = this._buildApp();
  }

  setRoots(roots) {
    this.library.setRoots(roots);
  }

  _buildApp() {
    const app = express();
    app.disable('x-powered-by');
    app.set('etag', false); // stream.js sets its own; express's would fight it

    // The TV app is not a browser, but the admin page and browser testing are.
    app.use((req, res, next) => {
      res.setHeader('Access-Control-Allow-Origin', '*');
      res.setHeader('Access-Control-Allow-Headers', 'Range, Content-Type');
      res.setHeader('Access-Control-Expose-Headers', 'Content-Range, Content-Length, Accept-Ranges');
      if (req.method === 'OPTIONS') return res.sendStatus(204);
      next();
    });

    app.get('/api/info', (req, res) => {
      res.json({
        name: this.name,
        version: VERSION,
        protocol: 1,
        shares: this.library.roots.length,
        addresses: lanAddresses().map((a) => a.address),
      });
    });

    app.get('/api/browse', (req, res) => {
      const result = this.library.browse(req.query.path || null);
      if (!result) return res.status(404).json({ error: 'folder_not_found' });
      res.json(result);
    });

    app.post('/api/rescan', (req, res) => {
      this.library.rebuildIndex();
      res.json({ ok: true, indexed: this.library.index.size });
    });

    // GET and HEAD both land here; express routes HEAD to the GET handler.
    app.get('/api/files/:id/stream', async (req, res) => {
      const entry = this.library.resolve(req.params.id);
      if (!entry || entry.isDir) return res.status(404).json({ error: 'file_not_found' });
      await sendFileRange(req, res, entry.path, path.extname(entry.path));
    });

    app.get('/api/files/:id/subtitles/:subId', async (req, res) => {
      const entry = this.library.resolve(req.params.subId);
      if (!entry || entry.isDir) return res.status(404).json({ error: 'subtitle_not_found' });

      const ext = path.extname(entry.path).toLowerCase();
      try {
        const text = await readSubtitle(entry.path);
        if (req.query.format === 'vtt' && ext === '.srt') {
          res.type('text/vtt; charset=utf-8').send(srtToVtt(text));
        } else if (ext === '.vtt') {
          res.type('text/vtt; charset=utf-8').send(text);
        } else {
          res.type('text/plain; charset=utf-8').send(text);
        }
      } catch (err) {
        res.status(500).json({ error: 'read_failed', detail: err.message });
      }
    });

    // Minimal browser page for verifying playback without the TV.
    app.use('/', express.static(path.join(__dirname, '..', 'web')));

    return app;
  }

  listen() {
    return new Promise((resolve, reject) => {
      // 0.0.0.0 so the TV can reach us; this is what triggers the firewall prompt.
      this.server = this.app.listen(this.port, '0.0.0.0');
      this.server.on('listening', () => {
        // Long-lived video sockets must not be reaped mid-playback.
        this.server.keepAliveTimeout = 120000;
        this.server.headersTimeout = 125000;
        this.server.requestTimeout = 0;
        resolve(this.server.address().port);
      });
      this.server.on('error', reject);
    });
  }

  async close() {
    this.library.dispose();
    if (!this.server) return;
    await new Promise((resolve) => this.server.close(resolve));
    this.server = null;
  }
}

module.exports = { VideoServer };
