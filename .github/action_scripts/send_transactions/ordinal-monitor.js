const axios = require('axios')
const { logWorkflow } = require('../shared')

const logMessage = (message) => {
  logWorkflow.info(message)
}

const sleep = (ms) => {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

const getLatestSnapshot = async (l0Url) => {
  try {
    const response = await axios.get(`${l0Url}/global-snapshots/latest`)
    return response.data
  } catch (error) {
    logMessage(`Error fetching latest snapshot: ${error.message}`)
    return null
  }
}

const monitorOrdinal = async () => {
  const args = process.argv.slice(2)
  
  if (args.length < 1) {
    throw new Error('Usage: node ordinal-monitor.js <dagl0-port-prefix>')
  }
  
  const dagL0PortPrefix = args[0]
  const l0Url = `http://localhost:${dagL0PortPrefix}00`
  
  logMessage(`Starting ordinal monitor on ${l0Url}`)
  logMessage('Polling every 500ms for ordinal changes...')
  
  let previousOrdinal = null
  let firstRun = true
  
  while (true) {
    const snapshot = await getLatestSnapshot(l0Url)
    
    if (snapshot && snapshot.ordinal !== undefined) {
      const currentOrdinal = snapshot.ordinal
      
      if (firstRun) {
        logMessage(`Initial ordinal: ${currentOrdinal}`)
        previousOrdinal = currentOrdinal
        firstRun = false
      } else if (currentOrdinal !== previousOrdinal) {
        const timestamp = new Date().toISOString()
        const change = currentOrdinal - previousOrdinal
        logMessage(`[${timestamp}] Ordinal changed: ${previousOrdinal} → ${currentOrdinal} (change: +${change})`)
        previousOrdinal = currentOrdinal
      }
    }
    
    await sleep(500)
  }
}

// Run the monitor
monitorOrdinal().catch((err) => {
  logMessage(`Monitor failed: ${err.message}`)
  console.error(err)
  process.exit(1)
})