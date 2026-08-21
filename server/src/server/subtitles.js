'use strict';

const fs = require('fs');

/**
 * Media3 parses SubRip natively, so .srt is served as-is. This converter exists
 * for the WebVTT path (browser <track> testing) and normalises the comma
 * decimal separator SubRip uses.
 */
function srtToVtt(srt) {
  const body = srt
    .replace(/^﻿/, '')
    .replace(/\r\n?/g, '\n')
    .replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2');
  return `WEBVTT\n\n${body}`;
}

/** Read a subtitle file, stripping a UTF-8 BOM that would break the first cue. */
async function readSubtitle(filePath) {
  const buf = await fs.promises.readFile(filePath);
  let text = buf.toString('utf8');
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
  return text;
}

module.exports = { srtToVtt, readSubtitle };
