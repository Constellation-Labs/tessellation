const { parseSharedArgs } = require('./validations')
const { CONSTANTS, PRIVATE_KEYS } = require('./constants')
const { generateProof, generateProofWithBrotli, brotliSerialize } = require('./signatures')
const { sleep, withRetry } = require('./operations')
const { getEpochProgress, createAndConnectAccount, createNetworkConfig } = require('./network')

module.exports = {
  CONSTANTS,
  PRIVATE_KEYS,

  parseSharedArgs,

  generateProof,
  generateProofWithBrotli,
  brotliSerialize,

  sleep,
  withRetry,

  getEpochProgress,
  createAndConnectAccount,
  createNetworkConfig
}