'use strict';
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

  const lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<resources>',
  ];

  for (const key of keys) {
    const val = master[key][lang];
    if (val === '') continue;
    lines.push(`    <string name="${key}">${escapeXml(val)}</string>`);
  }

  lines.push('</resources>');
  lines.push('');

  fs.writeFileSync(outPath, lines.join('\n'), 'utf8');

  const missing = total - translated.length;
  console.log(`${lang}: ${translated.length}/${total} translated, ${missing} missing → ${outPath}`);
}

console.log('\nDone.');
