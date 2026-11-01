import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRouter, createMemoryHistory } from 'vue-router'

const source = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')

// 复用真实路由和守卫，只替换浏览器历史、页面组件与本地存储。
const factory = new Function('createRouter', 'createWebHistory', 'localStorage', source
  .replace(/^import \{ createRouter, createWebHistory \} from 'vue-router'\s*/, '')
  .replace(/const (\w+) = \(\) => import\('[^']+'\)/g,
    (_, name) => 'const ' + name + ' = { name: "' + name + '", render: () => null }')
  .replace('export default router', 'return router'))

function setup(token = null) {
  return factory(createRouter, createMemoryHistory, {
    getItem: key => key === 'teamdocs_token' ? token : null
  })
}

test('root opens the login page instead of the landing page', async () => {
  const router = setup()
  await router.push('/')

  assert.equal(router.currentRoute.value.path, '/login')
  assert.equal(router.currentRoute.value.name, 'Login')
  assert.equal(router.currentRoute.value.redirectedFrom.path, '/')
})

test('root also redirects to login when a token is already present', async () => {
  const router = setup('existing-token')
  await router.push('/')

  assert.equal(router.currentRoute.value.path, '/login')
})

test('login remains public and does not redirect to itself', async () => {
  const router = setup()
  await router.push('/login')

  assert.equal(router.currentRoute.value.name, 'Login')
  assert.equal(router.currentRoute.value.redirectedFrom, undefined)
  assert.equal(router.currentRoute.value.meta.requiresAuth, false)
})

test('protected workbench and preview routes still require a token', async () => {
  for (const url of ['/home', '/spaces/20/agent/10', '/preview/20/17']) {
    const router = setup()
    await router.push(url)

    assert.equal(router.currentRoute.value.path, '/login')
  }
})

test('authenticated workbench, agent and preview routes remain available', async () => {
  const router = setup('existing-token')
  for (const [url, name] of [['/home', 'Home'], ['/spaces/20/agent/10', 'SpaceAgent'], ['/preview/20/17', 'DocumentPreview']]) {
    await router.push(url)

    assert.equal(router.currentRoute.value.name, name)
    assert.equal(router.currentRoute.value.meta.requiresAuth, true)
  }
})

test('unknown paths keep the existing authenticated home fallback', async () => {
  const router = setup('existing-token')
  await router.push('/unknown-path')

  assert.equal(router.currentRoute.value.path, '/home')
})

test('the removed landing page is no longer imported or kept as unused code', async () => {
  assert.doesNotMatch(source, /LandingView/)
  await assert.rejects(readFile(new URL('../src/views/LandingView.vue', import.meta.url)), { code: 'ENOENT' })
})
