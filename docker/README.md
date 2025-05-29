# Docker Scripts / Development Workflow

In this directory, there is a dockerfile covering GL0/gl1 in same image, entrypoints for running, 
and a docker compose meant to represent a single 'node' of compute running multiple services with a 
single identity.

Please use the justfile for passing arguments, and runnning the most common commands. All environment 
variables and arguments are exposed in `bin/set-env.sh` for the main `compose-runner.sh` script. Below 
is an explanation of the most common commands you might run during development.

`just build --version v3.2.0`

This will build a docker image off of your current directory, defaulting to only recompiling the L0 assembly 
unless all other jars are not found, in which case it will recompile all.

`just debug-main`

This is intended for deploying a custom version from your current git branch to a live mainnet network node 
for capturing debug information, heap dumps, profile data, or custom metrics or log statements. 

Requires rsync to be installed.

This assumes you have already set in your ~/.ssh/config the following:

```
Host genesis
  HostName 52.53.46.33
  User admin
Host dest
  HostName <your-node-ip-here>
  User root
```

Additionally, it assumes you have a keypair at /root/key.p12, and .env file at 
/root/.env containing the information necessary to load your keypair. Please fill out below 
as necessary, here is an example you need to replace with your own data.

```
ENV CL_KEYALIAS="alias"
ENV CL_PASSWORD="password"
```

The remainder of environment variables will be generated automatically, it is run on root on the remote VM, 
so should be used with a disposable VM or one you otherwise do not carry state on. It will auto-install 
all dependencies, only run the GL0, and tail the logs.