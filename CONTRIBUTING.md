# How to Contribute

_Last updated June 2, 2022._

Contributing to this project entails submitting pull requests (PRs) to this repository.

1. Fork this repository.
2. Clone your fork of the repository.
3. When developing, keep you changes as atomic as possible.
4. When you are ready to make a PR, fetch from upstream to make sure you have the latest changes from the upstream repository. Add your commit on top of any new commits from upstream:

```
git fetch origin
git rebase origin/develop
git checkout <your branch>
git rebase develop
```

5. Set your local branch to track its equivalent on the remote server. Push your changes to that remote branch.
6. Create a PR.
7. In your PR, note which of the below categories your PR belongs to.
   - Bug fix
   - Feature
   - Documentation
   - New/updated tests

# Running SBT in the command-line

Run these commands from the base directory of this project.

- `sbt compile` - compiles the project.
- `sbt test` - runs the tests.
- `sbt runLinter` - automatically formats the code according to the style guide.

# Setting Up SBT

After installing _SBT_, per the SETUP guide, you have to configure it.

1. Generate a _Github Personal Access Token_, [link](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token).
2. Set your global _SBT_ configuration.
   1. Create the _~/.sbt/1.0/github.sbt_ file.
   2. Copy into it below. Replace _---Token---_ with the Personal Access Token you generated and _---Github ID---_ with your Github username.

```
credentials +=
	Credentials(
		"Github Package Registry",
		"maven.pkg.github.com",
		"---Github Id---",
		"---Token---")

githubTokenSource := TokenSource.GitConfig("github.token")
```

3. Create the file _~/.sbt/1.0/plugins/plugin.sbt_.
   1. Copy into it: `addSbtPlugin("com.codecommit" % "sbt-github-packages" % "0.5.3")`
