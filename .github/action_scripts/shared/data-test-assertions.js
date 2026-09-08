function hasExpectedUsage(state, address) {
    return state?.usages?.deviceAddress === address && state.usages.deviceUsage === 10;
}

function getBalances(combined, metagraphId) {
    const balances = combined?.[1]?.lastCurrencySnapshots?.[metagraphId]?.Right?.[1]?.balances;
    if (!balances || typeof balances !== 'object') throw new Error('Metagraph balances not available yet');
    return balances;
}

function balanceOf(balances, address) {
    const value = balances[address] ?? 0;
    if (!Number.isSafeInteger(value) || value < 0) throw new Error('Invalid test balance');
    return value;
}

function hasExpectedFeeTransfer(before, after, source, destination, fee) {
    if (source === destination || !Number.isSafeInteger(fee) || fee <= 0) return false;
    return balanceOf(before, source) - balanceOf(after, source) === fee &&
        balanceOf(after, destination) - balanceOf(before, destination) === fee;
}

module.exports = { hasExpectedUsage, getBalances, balanceOf, hasExpectedFeeTransfer };
