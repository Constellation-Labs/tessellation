# 11. Project versioning and release flow

Date: 2022-05-26

## Status

Accepted

## Context

We need a versioning scheme for the project that will allow simultaneous development of the library modules (`kernel` and `sdk`) and the projects in the `examples/` directory that can depend 
on the library modules. SNAPSHOT versioning will allow this.

## Decision

1. `main` branch contains changes that have been released.
2. `develop` branch contains changes that haven't been released yet.
3. Project versioning follows [semver](https://semver.org).
4. `main` branch must contain version without precedence (`MAJOR.MINOR.PATCH`) - version `a.b.c-alpha` or `a.b.c-SNAPSHOT` is not allowed.
5. `develop` branch should reflect the next development version of the latest
   release, so `MAJOR.MINOR+1.0-SNAPSHOT`.

Example: when the latest released version is `v0.5.0`, the project version on `main` branch is `v0.5.0` while 
on `develop` branch, the project version is `v0.6.0-SNAPSHOT`.

6. For merging release or hotfix changes back into main or develop, `git merge` should be used, with fast-forward when possible.

### Release flow

1. Create branch from the `develop` or feature branch: `release/vA.B.C`.
2. Create commit with updated version for the release. (example: for development
   version `v1.5.0-SNAPSHOT` create a commit message `v1.5.0` with updated [version.sbt](../../version.sbt))
3. Merge release branch to `main` using `git merge`.
4. Create tag reflecting released version: `git tag vA.B.C`.
5. Push changes to origin `git push origin main --tags`.
6. Merge `main` branch to `develop` using `git merge`.
7. Create commit with an updated development version on `develop` branch.
   (example: released `v1.5.0`, create commit `v1.6.0-SNAPSHOT`)
8. Push changes to origin `git push origin develop`.

### Hotfixes flow

1. Create branch from the `main` branch by incrementing the patch version `hotfix/vA.B.C+1`. (example: latest release `v1.5.0`, branch name: `hotfix/v1.5.1`)
2. Create commit with hotfix development version `vA.B.C-SNAPSHOT` (example: `v1.5.1-SNAPSHOT`). It's because hotfix may contain more than one commit.
3. Develop fix by merging pull requests into the hotfix branch (pull requests are required to have a correct CHANGELOG for release).
4. Create commit with updated version and removed `-SNAPSHOT` precedence: `vA.B.C`.
5. Merge hotfix branch to `main` using `git merge`.
6. Create tag reflecting released version `git tag vA.B.C`.
7. Push changes to origin `git push origin main --tags`.
8. Merge hotfix branch to `develop` using `git merge`. Resolve conflicts if
   required (most likely version conflict).
9. Push changes to origin `git push origin develop`.

## Consequences

* Merge conflicts may arise when merging hotfixes into the `develop`.
