import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const config = await readFile(new URL('../nginx.conf', import.meta.url), 'utf8')

function moduleLocation() {
  const match = config.match(/location\s+~\*\s+\\\.mjs\$\s*\{([\s\S]*?)\n    \}/)
  assert.ok(match, 'ES modules need a dedicated MIME mapping')
  return match[1]
}

test('mjs files are served as JavaScript modules, not generic binary files', () => {
  assert.match(moduleLocation(), /types\s*\{\s*application\/javascript\s+mjs;\s*\}/)
})

test('unversioned module assets are revalidated and retain nosniff', () => {
  const location = moduleLocation()
  assert.match(location, /Cache-Control\s+"no-cache"/)
  assert.match(location, /X-Content-Type-Options\s+nosniff/)
  assert.doesNotMatch(location, /immutable|expires\s+1y/)
})

test('missing modules return 404 instead of the SPA HTML entry', () => {
  const location = moduleLocation()
  assert.match(location, /try_files\s+\$uri\s+=404;/)
  assert.doesNotMatch(location, /index\.html/)
})

test('other static assets keep their original MIME handling and cache policy', () => {
  assert.match(config, /location\s+~\*\s+\\\.\(\?:css\|js\|png/)
  assert.match(config, /expires\s+1y;/)
  assert.match(config, /location\s+\^~\s+\/api\//)
})
