const COLORS = {
    reset: '\x1b[0m',
    bright: '\x1b[1m',
    dim: '\x1b[2m',
    red: '\x1b[31m',
    green: '\x1b[32m',
    yellow: '\x1b[33m',
    blue: '\x1b[34m',
    magenta: '\x1b[35m',
    cyan: '\x1b[36m',
    white: '\x1b[37m',
};

const logWorkflow = {
    start: (name) => console.log(`${COLORS.cyan}🚀 Starting ${name} workflow${COLORS.reset}`),
    success: (name) => console.log(`${COLORS.green}✅ ${name} workflow completed successfully${COLORS.reset}`),
    error: (name, error) => console.error(`${COLORS.red}❌ ${name} workflow failed: ${error}${COLORS.reset}`),
    info: (message) => console.log(`${COLORS.blue}ℹ️  ${message}${COLORS.reset}`),
    warning: (message) => console.log(`${COLORS.yellow}⚠️  ${message}${COLORS.reset}`),
    debug: (message) => console.log(`${COLORS.dim}🔍 ${message}${COLORS.reset}`)
};

module.exports = {
    COLORS,
    logWorkflow
}; 