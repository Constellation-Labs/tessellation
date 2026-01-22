# 2. Service and storage responsibility

Date: 2022-01-11

## Status

Accepted

## Context

We have to decide what are responsibilities of `Storage` and `Service`.
For example, where DAG Blocks should be stored, where should be accepted, where
update tips, to keep state changes transactional (either all or nothing).

## Decision

1. Storage should keep the state and `Service` should be stateless.
2. We will add an extra logic to `Storage` (see `BlockStorage`) due to required
   local state changes.
3. DAG Block validation will be a separate service (stateless) that will have a
   single responsibility and can depend on `BlockStorage` to make required
   validation.
4. DAG Block acceptance will take place in `BlockService` that will aggregate
   additional dependencies.
5. `BlockStorage` will keep update methods (methods that changes the state) private for package if possible, publishing get methods only.
6. We may split the service logic into smaller services if needed (not
   required).

## Consequences

`Storage` will not be a clean repository pattern anymore. We all agree that we may add some
extra logic to `Storage` to keep state changes local. If there is no need for
such logic, we should keep `Storage` clean.
