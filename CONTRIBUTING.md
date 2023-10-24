# How to Contribute

## Code Level Contributions

Contributing to this project entails submitting pull requests (PRs) to this repository.

### Repository Fork

Create a repository fork via https://github.com/Constellation-Labs/tessellation/fork.

Create a clone of your repo (origin) and add the (quasi-standard named) `upstream` remote.

```sh
git clone https://github.com/<you-github-account>/tessellation
cd tessellation
git remote add upstream https://github.com/Constellation-Labs/tessellation
```

When developing, keep your changes as atomic as possible (e.g. a commit should not break the code/ci).

### Feature Branch

Create a Feature-Branch (example for branch '747-update-contrib')

```sh
git checkout -b 747-update-contrib
```

### Rebase

Especially for long running work, ensure that you sync from time to time your branch with the latest upstream changes.

```sh
git checkout 747-update-contrib
git fetch upstream
git rebase upstream/develop
```

### PR (Pull Request)
4. When you are ready to make a PR, again rebase with upstream as mentioned above. Then just push as usual:

```sh
# -u is the shtorcut for --set-upstream-to
git push -u origin 747-update-contrib
```

Create the PR from the github UI whilst following the instructions given there.


## Coding Style

- Try to mimic the existent coding style within the repository.
- Use Scala's formatter, [scalafmt](https://scalameta.org/scalafmt/), and [ScalaFix](https://scalacenter.github.io/scalafix/). Before committing, run `sbt runLinter`.

### Setting up _scalafmt_
- For IntelliJ, click [here](https://www.jetbrains.com/help/idea/work-with-scala-formatter.html).

## Running SBT in the command-line

Run these commands from the base directory of this project.

- `sbt compile` - compiles the project.
- `sbt test` - runs the tests.
- `sbt runLinter` - automatically formats the code according to the style guide.
