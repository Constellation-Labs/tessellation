const axios = require('axios');

const sleep = (ms) => {
  return new Promise((resolve) => setTimeout(resolve, ms));
};

const parseArgs = () => {
  const args = {};
  for (const arg of process.argv.slice(2)) {
    const eqIndex = arg.indexOf('=');
    if (eqIndex !== -1) {
      const key = arg.slice(0, eqIndex);
      const value = arg.slice(eqIndex + 1); // Preserve everything after first '='
      args[key.replace(/^-+/, '')] = value;
    }
  }
  return args;
};

const main = async () => {
  const args = parseArgs();
  
  if (!args.url) {
    throw Error('Url should be provided via -url=<url>');
  }

  const url = args.url;
  const clusterName = args.cluster_name || 'Node';
  const waitForReady = args.wait_for_ready === 'true';
  const maxAttempts = parseInt(args.max_attempts || '60', 10);
  const intervalSeconds = parseInt(args.interval || '10', 10);

  console.log(`Starting to check if url: ${url} is started`);
  if (waitForReady) {
    console.log(`Will wait for node to reach 'Ready' state (max ${maxAttempts * intervalSeconds}s)`);
  }

  for (let idx = 0; idx < maxAttempts; idx++) {
    try {
      const response = await axios.get(url, {
        headers: {
          Accept: 'application/json'
        },
        timeout: 5000
      });

      if (response.status === 200) {
        const state = response.data?.state;
        
        if (waitForReady) {
          if (state === 'Ready') {
            console.log(`${clusterName} is Ready`);
            return;
          }
          console.log(
            `${clusterName} state: ${state}, waiting for Ready... (${idx + 1}/${maxAttempts})`
          );
        } else {
          console.log(`${clusterName} started (state: ${state})`);
          return;
        }
      }
    } catch (e) {
      const errorMsg = e.code === 'ECONNREFUSED' ? 'connection refused' : e.message;
      console.log(
        `${clusterName} still booting (${errorMsg})... waiting ${intervalSeconds}s (${idx + 1}/${maxAttempts})`
      );
    }

    if (idx === maxAttempts - 1) {
      const waitType = waitForReady ? 'reach Ready state' : 'start';
      throw Error(`${clusterName} failed to ${waitType} after ${maxAttempts * intervalSeconds}s`);
    }

    await sleep(intervalSeconds * 1000);
  }
};

main();
