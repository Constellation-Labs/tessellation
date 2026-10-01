const assert = require('node:assert/strict')
const { test } = require('node:test')
const {
  getSingleStakeForLock,
  withStakeRetry,
  StakeInvariantViolation,
} = require('./stake-invariants')

const original = { hash: 'original', tokenLockRef: 'lock', acceptedOrdinal: 10 }
const replacement = {
  hash: 'replacement', tokenLockRef: 'lock', acceptedOrdinal: 11,
}
const options = { name: 'stake state', maxAttempts: 3, interval: 0 }

for (const [name, active, pending] of [
  ['active and pending', [replacement], [original]],
  ['duplicate active', [original, replacement], []],
  ['duplicate pending', [], [original, replacement]],
]) {
  test(`${name} fails immediately even if the next response would be safe`, async () => {
    let reads = 0
    await assert.rejects(
      withStakeRetry(async () => {
        reads += 1
        return getSingleStakeForLock(
          {
            activeDelegatedStakes: reads === 1 ? active : [replacement],
            pendingWithdrawals: reads === 1 ? pending : [],
          },
          'lock',
        )
      }, options),
      (error) =>
        error instanceof StakeInvariantViolation &&
        error.message.includes('token lock lock') &&
        error.message.includes('acceptedOrdinal'),
    )
    assert.equal(reads, 1)
  })
}

test('a valid original stake can propagate to the replacement without failing', async () => {
  let reads = 0
  const result = await withStakeRetry(async () => {
    reads += 1
    const state = getSingleStakeForLock(
      {
        activeDelegatedStakes: [reads === 1 ? original : replacement],
        pendingWithdrawals: [],
      },
      'lock',
    )
    if (state.activeForLock[0].hash !== replacement.hash) {
      throw new Error('Replacement is not active yet')
    }
    return state
  }, options)
  assert.equal(reads, 2)
  assert.deepEqual(result.activeForLock, [replacement])
})

test('pending withdrawal can resolve while unrelated token locks remain', async () => {
  let reads = 0
  const unrelated = { hash: 'unrelated', tokenLockRef: 'another-lock' }
  await withStakeRetry(async () => {
    reads += 1
    const state = getSingleStakeForLock(
      {
        activeDelegatedStakes: [unrelated],
        pendingWithdrawals: reads === 1 ? [original] : [],
      },
      'lock',
    )
    if (state.pendingForLock.length) throw new Error('Withdrawal has not resolved')
    assert.equal(state.activeForLock.length, 0)
  }, options)
  assert.equal(reads, 2)
})

test('a transient read error can recover, but exhausted retries preserve the cause', async () => {
  let reads = 0
  await withStakeRetry(async () => {
    if (++reads === 1) throw new Error('Temporary read failure')
  }, options)
  assert.equal(reads, 2)

  const failure = new Error('Withdrawal has not resolved for lock')
  await assert.rejects(
    withStakeRetry(async () => { throw failure }, options),
    (error) => error === failure,
  )
})
