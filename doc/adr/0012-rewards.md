# Rewards

Date: 2022-06-01

## Status

Proposed

## Context

Facilitators get rewarded in DAG currency for participating in consensus rounds.
We need to figure out the algorithm for minting/sending rewards to facilitators. Algorithm
should take dynamic network speed into account to determine the size of rewards per node.

## Decision

1. Each node will track the time difference between the creation of local snapshots.
We can either store the average or keep the specific number of meaningful diffs (e.g. 100 last diffs) and calculate the average on demand.
The calculated average is **local network speed** and will be proposed to `GlobalSnapshot` consensus.

Sample output: `3 min 15s`

2. The snapshot speed should be rounded (e.g. to minutes) to increase the probability of selecting the value which occurs most often (dominant value). There are 2 possible results of finding dominant value: found or not.

a) found dominant value

Sample input: `[3m 15s, 3m 3s, 5m 0s, 2m 13s]`
Sample output: `[3m, 3m, 5m, 2m]` (dominant value is `3m`)

b) didn't find dominant value

Sample input: `[3m 15s, 1m 3s, 5m 0s, 2m 13s]`
Sample output: `[3m, 1m, 5m, 2m]` (no dominant value)

In case of b) we need to use the **median** because **mean** is sensitive to extreme values.
An alternative could be **trimmed mean**, but it is hard to say which percent of extreme values
should be trimmed.

## Consequences

As a consequence the `GlobalSnapshot` contains the global network speed, so we can use that
as an input for calculating total rewards per snapshot. The negative outcome of the way of calculating dominant/median
is the fact that in case of median facilitators will get lower rewards even if most of the facilitators created
snapshot in time `t` where `t > median`. On the other hand, solution can't be broken by extreme values.
