'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const VIDEO_EXTS = new Set([
  '.mp4', '.mkv', '.avi', '.mov', '.m4v', '.webm',
  '.ts', '.flv', '.wmv', '.mpg', '.mpeg',
]);

const SUBTITLE_EXTS = new Set(['.srt', '.vtt', '.ass', '.ssa', '.sub']);

// Two-letter / three-letter language codes we recognise in "Movie.en.srt".
const LANG_RE = /^[a-z]{2,3}$/i;

function makeId(absPath) {
  // Stable, opaque, and short. Paths never cross the wire.
  return crypto.createHash('sha1').update(path.resolve(absPath).toLowerCase()).digest('hex').slice(0, 16);
}

class Library {
  constructor() {
    /** @type {string[]} absolute share roots */
    this.roots = [];
    /** @type {Map<string, {path: string, isDir: boolean}>} */
    this.index = new Map();
    this.watchers = [];
    this.onChange = null;
    this._rescanTimer = null;
  }

  setRoots(roots) {
    this.roots = roots.map((r) => path.resolve(r));
    this.rebuildIndex();
    this.restartWatchers();
  }

  /** Roots as browsable entries for the top level of the TV app. */
  listRoots() {
    return this.roots
      .filter((root) => {
        try {
          return fs.statSync(root).isDirectory();
        } catch {
          return false;
        }
      })
      .map((root) => ({
        id: makeId(root),
        name: path.basename(root) || root,
        type: 'folder',
      }));
  }

  /**
   * Walk every root and register each directory and video file in the index.
   * The index is what makes opaque ids resolvable; it is also the security
   * boundary, since only indexed paths can ever be served.
   */
  rebuildIndex() {
    this.index.clear();
    for (const root of this.roots) {
      this._walk(root);
    }
  }

  _walk(dir) {
    let stat;
    try {
      stat = fs.statSync(dir);
    } catch {
      return;
    }
    if (!stat.isDirectory()) return;

    this.index.set(makeId(dir), { path: dir, isDir: true });

    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return; // permission denied, etc. — skip quietly
    }

    for (const entry of entries) {
      const name = entry.name;
      if (name.startsWith('.') || name === '$RECYCLE.BIN' || name === 'System Volume Information') {
        continue;
      }
      const full = path.join(dir, name);

      if (entry.isDirectory()) {
        this._walk(full);
      } else if (entry.isFile()) {
        const ext = path.extname(name).toLowerCase();
        if (VIDEO_EXTS.has(ext) || SUBTITLE_EXTS.has(ext)) {
          this.index.set(makeId(full), { path: full, isDir: false });
        }
      }
    }
  }

  /** Resolve an id to an absolute path, re-verifying it sits under a share root. */
  resolve(id) {
    const entry = this.index.get(id);
    if (!entry) return null;
    if (!this.isUnderRoot(entry.path)) return null;
    return entry;
  }

  isUnderRoot(target) {
    const resolved = path.resolve(target);
    return this.roots.some((root) => {
      const rel = path.relative(root, resolved);
      return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
    });
  }

  /**
   * Children of a directory id: subfolders that contain video somewhere below,
   * plus the video files directly inside.
   */
  browse(dirId) {
    const entry = dirId ? this.resolve(dirId) : null;
    if (dirId && (!entry || !entry.isDir)) return null;

    const dir = entry ? entry.path : null;
    if (!dir) return { path: [], entries: this.listRoots() };

    let dirents;
    try {
      dirents = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return { path: this.breadcrumb(dir), entries: [] };
    }

    const folders = [];
    const files = [];

    for (const d of dirents) {
      if (d.name.startsWith('.')) continue;
      const full = path.join(dir, d.name);

      if (d.isDirectory()) {
        if (!this.containsVideo(full)) continue; // hide dead-end folders
        folders.push({ id: makeId(full), name: d.name, type: 'folder' });
      } else if (d.isFile()) {
        const ext = path.extname(d.name).toLowerCase();
        if (!VIDEO_EXTS.has(ext)) continue;
        let size = 0;
        try {
          size = fs.statSync(full).size;
        } catch { /* ignore */ }
        files.push({
          id: makeId(full),
          name: path.basename(d.name, ext),
          fileName: d.name,
          type: 'video',
          ext,
          size,
          subtitles: this.findSubtitles(full),
        });
      }
    }

    const collate = (a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' });
    folders.sort(collate);
    files.sort(collate);

    return { path: this.breadcrumb(dir), entries: [...folders, ...files] };
  }

  /** True if this directory or any descendant holds a video file. */
  containsVideo(dir, depth = 0) {
    if (depth > 6) return true; // stop probing very deep trees; assume yes
    let dirents;
    try {
      dirents = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return false;
    }
    const subdirs = [];
    for (const d of dirents) {
      if (d.name.startsWith('.')) continue;
      if (d.isFile() && VIDEO_EXTS.has(path.extname(d.name).toLowerCase())) return true;
      if (d.isDirectory()) subdirs.push(path.join(dir, d.name));
    }
    return subdirs.some((s) => this.containsVideo(s, depth + 1));
  }

  breadcrumb(dir) {
    const root = this.roots.find((r) => {
      const rel = path.relative(r, dir);
      return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
    });
    if (!root) return [];

    const crumbs = [{ id: makeId(root), name: path.basename(root) || root }];
    const rel = path.relative(root, dir);
    if (!rel) return crumbs;

    let current = root;
    for (const part of rel.split(path.sep)) {
      current = path.join(current, part);
      crumbs.push({ id: makeId(current), name: part });
    }
    return crumbs;
  }

  /**
   * Sidecar subtitles for a video: same directory, same base name.
   * "Movie.mkv" matches "Movie.srt", "Movie.en.srt", "Movie.forced.srt".
   */
  findSubtitles(videoPath) {
    const dir = path.dirname(videoPath);
    const base = path.basename(videoPath, path.extname(videoPath)).toLowerCase();

    let dirents;
    try {
      dirents = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return [];
    }

    const out = [];
    for (const d of dirents) {
      if (!d.isFile()) continue;
      const ext = path.extname(d.name).toLowerCase();
      if (!SUBTITLE_EXTS.has(ext)) continue;

      const stem = path.basename(d.name, ext).toLowerCase();
      if (stem !== base && !stem.startsWith(`${base}.`)) continue;

      // Everything after the video base name, e.g. "en" or "en.forced".
      const suffix = stem.slice(base.length).replace(/^\./, '');
      const firstTag = suffix.split('.')[0];
      const lang = LANG_RE.test(firstTag) ? firstTag.toLowerCase() : 'und';

      const full = path.join(dir, d.name);
      this.index.set(makeId(full), { path: full, isDir: false });

      out.push({
        id: makeId(full),
        label: suffix || 'Default',
        lang,
        ext,
      });
    }
    return out;
  }

  restartWatchers() {
    this.stopWatchers();
    for (const root of this.roots) {
      try {
        const w = fs.watch(root, { recursive: true }, () => this.scheduleRescan());
        w.on('error', () => { /* volume unplugged, etc. */ });
        this.watchers.push(w);
      } catch {
        // recursive watch is unsupported on some volumes; rescan endpoint covers it
      }
    }
  }

  stopWatchers() {
    for (const w of this.watchers) {
      try {
        w.close();
      } catch { /* ignore */ }
    }
    this.watchers = [];
  }

  scheduleRescan() {
    if (this._rescanTimer) clearTimeout(this._rescanTimer);
    this._rescanTimer = setTimeout(() => {
      this._rescanTimer = null;
      this.rebuildIndex();
      if (this.onChange) this.onChange();
    }, 1000);
  }

  dispose() {
    this.stopWatchers();
    if (this._rescanTimer) clearTimeout(this._rescanTimer);
  }
}

module.exports = { Library, makeId, VIDEO_EXTS, SUBTITLE_EXTS };
