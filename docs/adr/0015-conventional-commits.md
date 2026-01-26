# 0015. Conventional commits for automated versioning and releases

Date: 2024-07-30

## Status

Accepted

## Context

Our current process for versioning and releasing involves manual steps that are prone to errors and time-consuming.
Specifically, we need to manually assign a version and a label for each pull request, so the changelog for releases can be generated.
This manual process increases the overhead and the risk of human errors.

To streamline our workflow and minimize errors, we want to adopt a more automated approach for versioning and releasing.
By using conventional commits, we can automate the generation of the changelog and the versioning process. Furthermore, we aim to use GitHub Action plugins
to automate the release process when pushing into the `release/<env>` branches (`mainnet`, `testnet`, `integrationnet`).

By adopting conventional commits and automating our release process, we aim to create a more efficient, reliable, and less error-prone workflow for versioning and releases.

## Decision

We will adopt conventional commits to standardize our commit messages and automate our versioning and release process. This will include the following steps:

1. Adopt [conventional commits](https://www.conventionalcommits.org/en/v1.0.0/):
- All commits must follow the conventional commits format (e.g., `feat: add new feature`, `fix: correct a bug`). This will be ensured by GitHub action and/or local git hooks.
- This standardization will allow tools to automatically determine the version bump (major, minor, patch) based on the commit messages.
- Breaking changes would be determined individually per case.

2. Automate changelog generation:
- Use tools such as `conventional-changelog` to automatically generate the changelog from commit messages.

3. Automate versioning and releases with GitHub Actions:
- Implement GitHub Actions to automate the release process.
- When changes are pushed to `release/<env>` branches (`mainnet`, `testnet`, `integrationnet`), the actions will automatically:
    1. Determine the new version based on conventional commits.
    2. Update the version in the repository.
    3. Generate and update the changelog.
    4. Create a git tag for the release.
    5. Push the changes and the tag to the repository.

4. Branch and Release flow:
- The `main` branch will be removed.
- The `develop` branch will remain the same.
- Feature branches will be merged into `develop` branch.
- `develop` branch (or release branches) will be merged into `release/<env>` branches.

## Consequences

Positive consequences:
- We significantly reduce the manual work required for each release.
- Conventional commits ensure that versioning is consistent and changelogs are accurate.
- Automated process streamline the workflow, making it faster and less prone to human error.
- Clear and standardized commit messages improve collaboration and understanding among team members.

Negative consequences:
- Team members will need to learn and adopt the conventional commits standard.

