'use strict';

const fs = require('fs');

// Node's default is 64 KB. Multi-GB video reads do far fewer syscalls at 4 MB.
const HIGH_WATER_MARK = 4 * 1024 * 1024;

const MIME_TYPES = {
  '.mp4': 'video/mp4',
  '.m4v': 'video/mp4',
  '.mkv': 'video/x-matroska',
  '.webm': 'video/webm',
  '.avi': 'video/x-msvideo',
  '.mov': 'video/quicktime',
  '.ts': 'video/mp2t',
  '.flv': 'video/x-flv',
  '.wmv': 'video/x-ms-wmv',
  '.mpg': 'video/mpeg',
  '.mpeg': 'video/mpeg',
};

function contentTypeFor(ext) {
  return MIME_TYPES[ext.toLowerCase()] || 'application/octet-stream';
}

/**
 * Parse a single-range `Range` header against a known file size.
 *
 * Handles the three forms that matter:
 *   bytes=0-        -> from 0 to EOF
 *   bytes=500-999   -> explicit window
 *   bytes=-500      -> suffix: the last 500 bytes
 *
 * Returns null when there is no usable range header (caller sends a 200),
 * or {unsatisfiable: true} when the range falls outside the file (caller sends 416).
 * Multi-range requests are deliberately treated as "no range" — players never
 * send them for video, and honouring them would mean multipart/byteranges.
 */
function parseRange(header, size) {
  if (!header || typeof header !== 'string') return null;

  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match) return null;

  const [, rawStart, rawEnd] = match;
  if (rawStart === '' && rawEnd === '') return null;

  let start;
  let end;

  if (rawStart === '') {
    // Suffix range: last N bytes. N=0 is meaningless -> unsatisfiable.
    const suffixLength = Number(rawEnd);
    if (suffixLength === 0) return { unsatisfiable: true };
    start = Math.max(0, size - suffixLength);
    end = size - 1;
  } else {
    start = Number(rawStart);
    end = rawEnd === '' ? size - 1 : Number(rawEnd);
    // A range beyond EOF is clamped, not rejected (RFC 9110).
    if (end > size - 1) end = size - 1;
  }

  if (!Number.isFinite(start) || !Number.isFinite(end)) return null;
  if (start > end || start >= size) return { unsatisfiable: true };

  return { start, end };
}

function weakEtag(stat) {
  return `W/"${stat.size.toString(16)}-${Math.floor(stat.mtimeMs).toString(16)}"`;
}

/**
 * Stream a file with byte-range support. Handles GET and HEAD.
 *
 * `resolveFile` is an async function (req) => {path, ext} | null, so the caller
 * owns id-to-path resolution and its own security checks.
 */
async function sendFileRange(req, res, filePath, ext) {
  let stat;
  try {
    stat = await fs.promises.stat(filePath);
  } catch {
    res.status(404).json({ error: 'file_not_found' });
    return;
  }

  if (!stat.isFile()) {
    res.status(404).json({ error: 'not_a_file' });
    return;
  }

  const size = stat.size;
  const range = parseRange(req.headers.range, size);

  res.setHeader('Accept-Ranges', 'bytes');
  res.setHeader('Content-Type', contentTypeFor(ext));
  res.setHeader('Last-Modified', stat.mtime.toUTCString());
  res.setHeader('ETag', weakEtag(stat));
  // Video is already compressed; keep proxies and middleware from touching it.
  res.setHeader('Cache-Control', 'no-transform');

  if (range && range.unsatisfiable) {
    res.setHeader('Content-Range', `bytes */${size}`);
    res.status(416).end();
    return;
  }

  const start = range ? range.start : 0;
  const end = range ? range.end : size - 1;
  const length = size === 0 ? 0 : end - start + 1;

  if (range) {
    res.status(206);
    res.setHeader('Content-Range', `bytes ${start}-${end}/${size}`);
  } else {
    res.status(200);
  }
  res.setHeader('Content-Length', String(length));

  // HEAD gets identical headers and no body — ExoPlayer and probes rely on this.
  if (req.method === 'HEAD' || length === 0) {
    res.end();
    return;
  }

  const stream = fs.createReadStream(filePath, {
    start,
    end,
    highWaterMark: HIGH_WATER_MARK,
  });

  // ExoPlayer aborts the connection on every seek. Without this the descriptor
  // and its 4 MB buffer leak on each one.
  const cleanup = () => stream.destroy();
  res.on('close', cleanup);

  stream.on('error', (err) => {
    res.removeListener('close', cleanup);
    if (!res.headersSent) {
      res.status(500).json({ error: 'read_failed', detail: err.message });
    } else {
      res.destroy(err);
    }
  });

  stream.pipe(res);
}

module.exports = { sendFileRange, parseRange, contentTypeFor, MIME_TYPES };
