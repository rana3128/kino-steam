'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

/**
 * Persisted settings, shared by the Electron app and the headless entry point.
 *
 * Location is independent of both the install directory and the shared media
 * directories: %APPDATA%\kino-server\config.json on Windows. Electron passes its
 * own userData path in so packaged and headless runs agree on one file.
 */
const DEFAULTS = {
  port: 8080,
  name: os.hostname(),
  folders: [],
  startOnLogin: false,
};

function defaultConfigDir() {
  const base = process.env.APPDATA
    || (process.platform === 'darwin'
      ? path.join(os.homedir(), 'Library', 'Application Support')
      : path.join(os.homedir(), '.config'));
  return path.join(base, 'kino-server');
}

class Config {
  constructor(dir = defaultConfigDir()) {
    this.dir = dir;
    this.file = path.join(dir, 'config.json');
    this.data = { ...DEFAULTS };
    this.load();
  }

  load() {
    try {
      const raw = fs.readFileSync(this.file, 'utf8');
      const parsed = JSON.parse(raw);
      this.data = { ...DEFAULTS, ...parsed };
      // Guard against a hand-edited file with the wrong shape.
      if (!Array.isArray(this.data.folders)) this.data.folders = [];
      this.data.folders = this.data.folders.map((f) => path.resolve(f));
    } catch {
      this.data = { ...DEFAULTS }; // missing or corrupt -> defaults
    }
    return this.data;
  }

  save() {
    fs.mkdirSync(this.dir, { recursive: true });
    fs.writeFileSync(this.file, JSON.stringify(this.data, null, 2), 'utf8');
  }

  get(key) {
    return this.data[key];
  }

  set(key, value) {
    this.data[key] = value;
    this.save();
  }

  /** Only folders that still exist — drives and network shares come and go. */
  existingFolders() {
    return this.data.folders.filter((f) => {
      try {
        return fs.statSync(f).isDirectory();
      } catch {
        return false;
      }
    });
  }

  addFolders(paths) {
    const current = new Set(this.data.folders.map((f) => f.toLowerCase()));
    let added = 0;
    for (const p of paths) {
      const abs = path.resolve(p);
      if (current.has(abs.toLowerCase())) continue;
      // Skip a folder already covered by an existing share root.
      const nested = this.data.folders.some((root) => {
        const rel = path.relative(root, abs);
        return rel !== '' && !rel.startsWith('..') && !path.isAbsolute(rel);
      });
      if (nested) continue;
      this.data.folders.push(abs);
      current.add(abs.toLowerCase());
      added++;
    }
    if (added) this.save();
    return added;
  }

  removeFolder(target) {
    const abs = path.resolve(target).toLowerCase();
    const before = this.data.folders.length;
    this.data.folders = this.data.folders.filter((f) => f.toLowerCase() !== abs);
    if (this.data.folders.length !== before) this.save();
    return before !== this.data.folders.length;
  }
}

module.exports = { Config, defaultConfigDir, DEFAULTS };
