# Docker Scripts / Development Workflow

In this directory, there is a dockerfile covering GL0/Dag-L1 in same image, entrypoints for running, 
and a docker compose meant to represent a single 'node' of compute running multiple services with a 
single identity.

Please use the justfile for passing arguments, and runnning the most common commands. All environment 
variables and arguments are exposed in `bin/set-env.sh` for the main `compose-runner.sh` script. Below 
is an explanation of the most common commands you might run during development.

`just build --version v3.2.0`

This will build a docker image off of your current directory, defaulting to only recompiling the L0 assembly 
unless all other jars are not found, in which case it will recompile all.

`just debug-main`

This assumes you have already set in your ~/.ssh/config the following:

```
Host genesis
  HostName 52.53.46.33
  User admin
Host dest
  HostName <your-node-ip-here>
  User root
```