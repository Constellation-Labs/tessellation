// Offline HTTP fixtures: exercise the real gate and entrypoint join function, without JVMs/Docker.
const assert = require('node:assert/strict')
const { execFile } = require('node:child_process')
const fs = require('node:fs/promises')
const http = require('node:http')
const os = require('node:os')
const path = require('node:path')
const { test } = require('node:test')
const { promisify } = require('node:util')

const run = promisify(execFile)
const dockerDir = path.resolve(__dirname, '../../docker')
const gate = path.join(dockerDir, 'wait-for-join-ready.sh')
const seedId = 'fixture-seed'
const registered = (state = 'Ready') => ({ id: seedId, state, session: 1, clusterSession: 1 })

async function fixture(t, options = {}) {
  const calls = []
  const server = http.createServer((req, res) => {
    calls.push({ method: req.method, url: req.url, at: Date.now() })
    const count = calls.filter((c) => c.url === req.url).length
    res.setHeader('Content-Type', 'application/json')
    if (req.url === '/node/state') {
      if (options.hangState) return
      res.end(JSON.stringify(options.state ? options.state(count) : 'ReadyToJoin'))
    } else if (req.url === '/registration/request') {
      if (options.hangSeed) return
      if (options.seedStatus) res.statusCode = options.seedStatus
      res.end(JSON.stringify(options.registration ? options.registration(count) : registered()))
    } else if (req.url === '/cluster/join' && req.method === 'POST') {
      res.end()
    } else if (options.hangCli) {
      // A listening socket with an unresponsive HTTP server is not a usable CLI.
    } else {
      res.writeHead(404).end()
    }
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  t.after(() => {
    server.closeAllConnections()
    server.close()
  })
  const port = String(server.address().port)
  if (options.startDelay) {
    await new Promise((resolve) => server.close(resolve))
    const timer = setTimeout(() => server.listen(Number(port), '127.0.0.1'), options.startDelay)
    t.after(() => clearTimeout(timer))
  }
  // Deliberately do not inherit credentials or unrelated deployment environment variables.
  const env = {
    PATH: process.env.PATH,
    CL_PUBLIC_HTTP_PORT: port,
    CL_DOCKER_ID: 'gl0',
    CL_DOCKER_JOIN: 'true',
    CL_DOCKER_WAIT_FOR_JOIN_READY: 'true',
    CL_DOCKER_JOIN_CLI_PORT: port,
    CL_DOCKER_JOIN_IP: '127.0.0.1',
    CL_DOCKER_JOIN_PORT: port,
    CL_DOCKER_JOIN_ID: seedId,
    CL_DOCKER_JOIN_INITIAL_DELAY: '0',
    CL_DOCKER_JOIN_RETRIES: '1',
    CL_DOCKER_JOIN_DELAY: '0',
    CL_DOCKER_JOIN_READY_TIMEOUT_SECONDS: '4',
  }
  return { env, calls }
}

async function join(t, env) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'e2e-join-ready-'))
  t.after(() => fs.rm(dir, { recursive: true, force: true }))
  const entrypoint = await fs.readFile(path.join(dockerDir, 'entrypoint.sh'), 'utf8')
  const end = entrypoint.indexOf('\njoin_process &')
  assert.ok(end > 0, 'entrypoint still exposes its background join function')
  // Source the actual function without starting the JVM or deployment initialization.
  await fs.writeFile(path.join(dir, 'join-function.sh'), entrypoint.slice(0, end))
  await fs.copyFile(gate, path.join(dir, 'wait-for-join-ready.sh'))
  return run('bash', ['-c', 'source ./join-function.sh; join_process'], { cwd: dir, env, timeout: 8000 })
}

test('slow local startup is polled before consuming the only join attempt', async (t) => {
  const { env, calls } = await fixture(t, { state: (n) => n < 2 ? 'StartingSession' : 'ReadyToJoin' })
  const result = await join(t, env)
  assert.match(result.stdout, /E2E join ready/)
  assert.match(result.stdout, /Joining cluster \(attempt 1\)/)
  assert.equal(calls.filter((c) => c.url === '/node/state').length, 2)
  assert.equal(calls.filter((c) => c.method === 'POST').length, 1)
  assert.equal(calls.at(-1).url, '/cluster/join')
})

test('connection refusal before JVM listeners start does not consume a join attempt', async (t) => {
  const { env, calls } = await fixture(t, { startDelay: 250 })
  const result = await join(t, env)
  assert.match(result.stdout, /Joining cluster \(attempt 1\)/)
  assert.equal(calls.filter((c) => c.method === 'POST').length, 1)
})

test('seed bootstrap is polled until an active registration exists', async (t) => {
  const { env, calls } = await fixture(t, { registration: (n) => n < 2 ? {} : registered() })
  await join(t, env)
  assert.equal(calls.filter((c) => c.url === '/registration/request').length, 2)
  assert.equal(calls.filter((c) => c.method === 'POST').length, 1)
})

for (const state of ['WaitingForObserving', 'Observing', 'WaitingForReady', 'Ready', 'WaitingForDownload', 'DownloadInProgress']) {
  test(`seed in ${state} does not require full-cluster readiness`, async (t) => {
    const { env } = await fixture(t, { registration: () => registered(state) })
    await run('bash', [gate], { env, timeout: 5000 })
  })
}

for (const [name, options, stage] of [
  ['non-ready local state', { state: () => 'StartingSession' }, 'local_state'],
  ['malformed local state', { state: () => ({ healthy: true }) }, 'local_state'],
  ['unresponsive local state', { hangState: true }, 'local_state'],
  ['unresponsive CLI', { hangCli: true }, 'local_cli'],
  ['unresponsive seed', { hangSeed: true }, 'seed_registration'],
  ['failed seed HTTP response', { seedStatus: 503 }, 'seed_registration'],
  ['wrong seed identity', { registration: () => ({ ...registered(), id: 'wrong' }) }, 'seed_registration'],
  ['seed without a session', { registration: () => ({ ...registered(), session: null }) }, 'seed_registration'],
  ['seed without a cluster session', { registration: () => ({ ...registered(), clusterSession: null }) }, 'seed_registration'],
  ['seed not yet in a cluster', { registration: () => registered('ReadyToJoin') }, 'seed_registration'],
]) {
  test(`${name} fails within the budget without posting a join`, async (t) => {
    const { env, calls } = await fixture(t, options)
    // SECONDS has whole-second granularity; leave time for prerequisite probes.
    env.CL_DOCKER_JOIN_READY_TIMEOUT_SECONDS = '2'
    await assert.rejects(join(t, env), (err) => {
      assert.equal(err.code, 1)
      assert.match(err.stderr, new RegExp(`waiting_for=${stage}`))
      assert.match(err.stderr, /auto-join aborted/)
      assert.doesNotMatch(err.stdout, /Join complete/)
      return true
    })
    assert.equal(calls.filter((c) => c.method === 'POST').length, 0)
  })
}

test('initial late-join delay remains ahead of readiness polling', async (t) => {
  const { env, calls } = await fixture(t)
  env.CL_DOCKER_JOIN_INITIAL_DELAY = '1'
  const started = Date.now()
  await join(t, env)
  assert.ok(calls[0].at - started >= 950, 'no readiness probe or join before the delay')
})

test('non-joining genesis/rollback lead bypasses the gate', async (t) => {
  const { env, calls } = await fixture(t)
  env.CL_DOCKER_JOIN = 'false'
  await join(t, env)
  assert.equal(calls.length, 0)
})

test('non-test default retains the original auto-join path', async (t) => {
  const { env, calls } = await fixture(t, { state: () => 'StartingSession' })
  delete env.CL_DOCKER_WAIT_FOR_JOIN_READY
  await join(t, env)
  assert.deepEqual(calls.map((c) => c.url), ['/cluster/join'])
})

test('readiness gate can be disabled explicitly for comparison', async (t) => {
  const { env, calls } = await fixture(t)
  env.CL_DOCKER_WAIT_FOR_JOIN_READY = 'false'
  await join(t, env)
  assert.deepEqual(calls.map((c) => c.url), ['/cluster/join'])
})

test('invalid readiness budget fails immediately', async (t) => {
  const { env, calls } = await fixture(t)
  env.CL_DOCKER_JOIN_READY_TIMEOUT_SECONDS = '0'
  await assert.rejects(join(t, env), (err) => {
    assert.equal(err.code, 1)
    assert.match(err.stderr, /must be a positive integer/)
    return true
  })
  assert.equal(calls.length, 0)
})

test('missing container port aborts without posting a join', async (t) => {
  const { env, calls } = await fixture(t)
  delete env.CL_PUBLIC_HTTP_PORT
  await assert.rejects(join(t, env), (err) => {
    assert.equal(err.code, 1)
    assert.match(err.stderr, /CL_PUBLIC_HTTP_PORT/)
    assert.match(err.stderr, /auto-join aborted/)
    return true
  })
  assert.equal(calls.length, 0)
})
