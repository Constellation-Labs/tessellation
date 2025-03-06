const { CONSTANTS } = require("./constants");

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

const withRetry = async (operation, {
    name = 'operation',
    maxAttempts = CONSTANTS.MAX_VERIFICATION_ATTEMPTS,
    interval = CONSTANTS.VERIFICATION_INTERVAL_MS,
    handleError = (error, attempt) => {
        if (error.response?.status === 404) {
            console.log(`${name} not found yet. Attempt ${attempt}`);
        } else {
            console.error(`${name} attempt ${attempt} failed: ${error.message}`);
        }
    }
} = {}) => {
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            return await operation();
        } catch (error) {
            handleError(error, attempt);

            if (attempt === maxAttempts) {
                throw new Error(`Max attempts reached for ${name}`);
            }

            await sleep(interval);
        }
    }
};

module.exports = {
    sleep,
    withRetry
}