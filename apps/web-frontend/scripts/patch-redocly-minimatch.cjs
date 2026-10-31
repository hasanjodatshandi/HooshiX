'use strict';
// shortcut: Redocly 1.x needs the old export, remove when upstream supports minimatch 11.
const { createHash } = require('node:crypto');
const { readFileSync, writeFileSync } = require('node:fs');
const { createRequire } = require('node:module');
let resolveFromOpenapi;
try {
  resolveFromOpenapi = createRequire(require.resolve('openapi-typescript'));
} catch (error) {
  if (error.code === 'MODULE_NOT_FOUND' && process.env.NODE_ENV === 'production') process.exit(0);
  throw error;
}
const path = resolveFromOpenapi.resolve('@redocly/openapi-core/lib/utils.js');
const content = readFileSync(path, 'utf8');
const expected = '896a9acce92e3e5905aa267e895985d57888a87f500d58c29da9c8f9d488ff1c';
const patched = '825736f13fb67dc4a1e2157bb51396209c7343cfd00376ed5d923e3e9ff82a18';
const digest = createHash('sha256').update(content).digest('hex');
if (digest === patched) process.exit(0);
if (digest !== expected) {
  throw new Error('Unexpected Redocly source; review the minimatch compatibility patch');
}
const oldImport = 'const minimatch = require("minimatch");';
if (!content.includes(oldImport) || content.split(oldImport).length !== 2) {
  throw new Error('Unexpected Redocly minimatch import');
}
writeFileSync(path, content.replace(oldImport, 'const { minimatch } = require("minimatch");'));
