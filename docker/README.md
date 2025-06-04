# Docker Development Workflow

In this directory, there is a Dockerfile covering all major internal services within the same image, 
using a unified entrypoint and health check. There is a docker-compose.yaml covering the GL0 / dag-l1 
core services, along with additional compose overrides for common deployment or test patterns. There 
is also a docker-compose.metagraph.yaml demonstrating the basic template used for running ML0/ML1/DL1 
services. Each set of compose files is meant to represent a single 'node' or deployment, with the expectation 
that you would run N unique services per compute node (sharing the same keypair.)

This is primarily intended for internal developers, although it can also be used for custom deployments. 
For the most common runtime commands, please refer to the `justfile` in the top level directory. You 
can also use `./docker/run.sh` to automatically install just and run commands with the same arguments.

All environment variables and arguments for justfile commands are exposed in `bin/set-env.sh` for the 
main `compose-runner.sh` script. Below are listed the most common commands you might run during development.


## Frequently Used Developer Commands

`just test`

This assembles L0 by default using the auto-incremental recompilation cache, and if no jars 
are found, it will build all of them with sbt assembly, and invoke all tests that only require the gl0 / dag-l0. By default, this test will NOT stop docker containers after running, nor will it cleanup 
the environment afterwards. By default, this will kill all docker containers / volumes with names defined in here, so it 
requires exclusive access to container names (unless alternatively configured.)

`just up` 

This runs all the commands required to setup and start all relevant containers (in common with `just test`), but 
skips the cluster start checks and end to end tests.

`just down`

Cleanup all docker containers / artifacts created by `just up`

`just build`

Same as `up`, except skips the container start.

`just test --cleanup-docker-at-end` 

Will run same options as before, but enforce a cleanup / terminate containers at end of test. 

`just test --use-test-metagraph`

The same as the prior command, except this will use the locally defined template metagraph and invoke all tests, including the tests that require the 'default' test metagraph.

`just test --metagraph=./your-path-to-a-different-metagraph`

Rather than using a test metagraph, this uses a real metagraph repository located at a local directory. 
By default, this will assemble the ML0, please use `--ml1` to also include metagraph layer 1, and `--dl1` to assemble data layer 1. By default, if any jars are missing, this will assemble them all. Additionally, 
if making changes to tessellation and a metagraph at the same time, please use `--publish` to enforce 
locally publishing the required artifacts, or if you want to compile yourself with whatever options just use

`just test --skip-assembly --metagraph=../ded --skip-metagraph-assembly`


`just test --skip-assembly`

 To quickly rebuild the docker images / clear the data and re-build your environment, instead run with the skip-assembly option to avoid all sbt assembly, again, if no jars are found, it will assemble all required for first run.

`just test --clean-assembly`

The `test` command does not run `sbt clean` by default, including this will wipe 
out the sbt incremental cache and `target` folders.

`just test --l1`

Use this command if you made changes to the L1, it will include it in the assembly 
process, and update the jars in the dockerfile as part of that.

`just clean`

This will purge all docker containers, sbt cache, target folder, and node environment deployment directories.

`just clean-docker`

This cleans all script / docker related local artifacts

`just build --version v3.2.0`

This will build a docker image off of your current directory, setting the version to a custom value.

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