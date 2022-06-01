# SETUP

This guide is to get you setup with the tools you'll need for development.

**Date**: May 24, 2002

**Platform**: Fedora 35, 64-bit

1. Install _SDKMan_, [link](https://sdkman.io/install).
   1. `curl -s "https://get.sdkman.io" | bash`
   2. `source "$HOME/.sdkman/bin/sdkman-init.sh"`
   3. Verify the installation: `sdk version`
2. Install _Coretto Java 8_, [link](https://sdkman.io/usage).
   1. List the versions: `sdk list java`
   2. Install the desired versions: `sdk install java 8.332.08.1-amzn`
   3. If _JAVA_HOME_ is not set, re-source SDKMan's init script: `source "$HOME/.sdkman/bin/sdkman-init.sh"`
3. Install the latest version of _Scala_: `sdk install scala`
4. Install the latest version of _SBT_: `sdk install sbt`

# Starting A Local Cluster (Optional)

To spin up a local cluster, we'll need a way to run [Kubernetes](https://kubernetes.io/) containers. One way is with [Docker](https://www.docker.com/). Running a local cluster is needed to run the integration tests.

1. Install _Docker Engine_, _containerd_, and the _Docker Compose_ plugin, [link](https://docs.docker.com/engine/install/fedora/).
   1. Install Docker using the package repository, [link](https://docs.docker.com/engine/install/fedora/#install-using-the-repository).
   2. Do the post install for _Docker_, [link](https://docs.docker.com/engine/install/linux-postinstall/)
2. Install _Docker Desktop_, [link](https://docs.docker.com/desktop/linux/install/fedora/).
   1. Download the _RPM_ package, [link](https://docs.docker.com/desktop/release-notes/).
3. Enable _Kubernetes_ on _Docker_.
   1. Start _Docker Desktop_.
   2. Click on the _Gear_ icon (i.e., settings).
   3. Click on the _Kubernetes_ menu item.
   4. Check the _Enable Kubernetes_ checkbox.
   5. Click the _Apply & Restart_ button.
4. Register for a _Docker Hub_ account and activate your account, [link](https://hub.docker.com/).
5. Login to _Docker_, [link](https://docs.docker.com/desktop/linux/#credentials-management).
6. Install _docker-credential-pass_, [link](https://github.com/docker/docker-credential-helpers/releases).
   1. Download the appropriate tarball for your machine and extract it.
   2. `sudo cp /path/to/docker-credential-pass /bin/docker-credential-desktop`
7. Install _kubectl_, [link](https://kubernetes.io/docs/tasks/tools/install-kubectl-linux/).
   1. Ensure that _kubectl_ is pointing to the Docker Desktop context:
      1. `kubectl config get-contexts`
      2. `kubectl config use-context docker-desktop`
