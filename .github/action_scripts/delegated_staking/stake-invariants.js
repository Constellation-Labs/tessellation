const { withRetry } = require('../shared/operations')

class StakeInvariantViolation extends Error {}

const getSingleStakeForLock = (state, lockHash) => {
  const activeForLock = state.activeDelegatedStakes.filter(
    (stake) => stake.tokenLockRef === lockHash,
  )
  const pendingForLock = state.pendingWithdrawals.filter(
    (stake) => stake.tokenLockRef === lockHash,
  )

  if (activeForLock.length + pendingForLock.length > 1) {
    throw new StakeInvariantViolation(
      `Multiple stake records reference token lock ${lockHash}: active=${JSON.stringify(activeForLock)}, pending=${JSON.stringify(pendingForLock)}`,
    )
  }

  return { activeForLock, pendingForLock }
}

// Retry propagation/settlement delays, but never forgive an observed violation.
const withStakeRetry = (operation, options) =>
  withRetry(operation, {
    ...options,
    handleError: (error, attempt) => {
      if (
        error instanceof StakeInvariantViolation ||
        attempt === options.maxAttempts
      ) {
        throw error
      }
    },
  })

module.exports = { getSingleStakeForLock, withStakeRetry, StakeInvariantViolation }
