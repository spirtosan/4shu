'use strict';
// NOTE: When generating the default English output (values/strings.xml), this script
// reads the existing file first and preserves all <plurals> blocks from it.
// Plurals are not stored in strings_master.json — they are managed manually in
// values/strings.xml and must not be removed when regenerating.
// values-bg and values-ru do not include plurals (Android falls back to default).
const fs = require('fs');
const path = require('path');

const masterPath = path.join(__dirname, 'strings_master.json');
const master = JSON.parse(fs.readFileSync(masterPath, 'utf8'));

const langs = {
  en: path.join(__dirname, '../app/src/main/res/values/strings.xml'),
  bg: path.join(__dirname, '../app/src/main/res/values-bg/strings.xml'),
  ru: path.join(__dirname, '../app/src/main/res/values-ru/strings.xml'),
};

function escapeXml(val) {
  return val
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/'/g, "\\'")
    .replace(/"/g, '\\"');
}

const keys = Object.keys(master);
const total = keys.length;

console.log(`Loaded ${total} strings from strings_master.json\n`);

for (const [lang, outPath] of Object.entries(langs)) {
  const translated = keys.filter(k => master[k][lang] !== '');

  if (lang !== 'en' && translated.length === 0) {
    console.log(`${lang}: all empty — skipping`);
    continue;
  }

  fs.mkdirSync(path.dirname(outPath), { recursive: true });

  // For the default (en) output, preserve existing <plurals> blocks.
  let pluralsBlocks = [];
  if (lang === 'en' && fs.existsSync(outPath)) {
    const existing = fs.readFileSync(outPath, 'utf8');
    const matches = existing.match(/ {4}<plurals[\s\S]*?<\/plurals>/g);
    if (matches) pluralsBlocks = matches;
  }

  const lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<resources>',
  ];

  for (const key of keys) {
    const val = master[key][lang];
    if (val === '') continue;
    lines.push(`    <string name="${key}">${escapeXml(val)}</string>`);
  }

  if (pluralsBlocks.length > 0) {
    lines.push('');
    for (const block of pluralsBlocks) {
      lines.push(block);
    }
  }

  lines.push('</resources>');
  lines.push('');

  fs.writeFileSync(outPath, lines.join('\n'), 'utf8');

  const missing = total - translated.length;
  console.log(`${lang}: ${translated.length}/${total} translated, ${missing} missing → ${outPath}`);
}

console.log('\nDone.');
