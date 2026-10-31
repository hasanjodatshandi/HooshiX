'use strict';
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const redocly = createRequire(require.resolve('openapi-typescript'))('@redocly/openapi-core/lib/utils.js');
async function check(pattern, expected) {
  await redocly.readFileFromUrl('https://example.test/schema.json', {
    headers: [{ matches: pattern, name: 'x-fixture', value: 'match' }],
    customFetch: async (_url, options) => {
      assert.equal(options.headers['x-fixture'], expected);
      return { ok: true, text: async () => '{}', headers: { get: () => 'application/json' } };
    },
  });
}
(async () => {
  await check('example.test/**', 'match');
  await check('other.test/**', undefined);
})().catch((error) => { console.error(error); process.exitCode = 1; });
