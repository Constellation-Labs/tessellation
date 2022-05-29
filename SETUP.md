# Install Guide

**Date**: May 24, 2022

**Platform**: Fedora 35 64-bit

1. Install SDKMan, [link](https://sdkman.io/install)
   1. Run in a terminal: `curl -s "https://get.sdkman.io" | bash`
   2. Run in a terminal: `source "$HOME/.sdkman/bin/sdkman-init.sh"`
   3. Run in a terminal to verify the installation: `sdk version`
2. Install Correto Java 8, [link](https://sdkman.io/usage).
   1. List the versions available and find the appropriate one: `sdk list java`
      ![Available java versions](./images/available_java_versions.png)
   2. Install it: `sdk install java 8.332.08.1-amzn`
   3. If the _JAVA_HOME_ environment variable is not set, source the _sdkman-init.sh_ again: `source "$HOME/.sdkman/bin/sdkman-init.sh"`
      ![Set $JAVA_HOME](./images/set_JAVA_HOME.png)
3. Install Scala: `sdk install scala`
4. Install SBT: `sdk install sbt`
5. Install Docker Desktop, [link](https://docs.docker.com/desktop/linux/install/fedora/).
   1. Setup the Docker repository, [link](https://docs.docker.com/engine/install/fedora/#set-up-the-repository)
      1. `sudo dnf -y install dnf-plugins-core`
      2. `sudo dnf config-manager --add-repo https://download.docker.com/linux/fedora/docker-ce.repo`
   2. Download the Docker Desktop RPM package, [link](https://docs.docker.com/desktop/release-notes/).
   3. Install the package: `sudo dnf install ./docker-desktop-<version>-<arch>.rpm`
   4. Enable Kubernetes.
      1. Start Docker Desktop via the Start Menu icon.
      2. Click on the **Gear** icon located in the top bar.
      3. Select **Kubernetes** in the left menu.
      4. Check the **Enable Kubernetes** checkbox.
      5. Click on the **Apply & Restart** button.
6. Do the post-install steps for Docker, [link](https://docs.docker.com/engine/install/linux-postinstall/).
   1. Enable the docker services and restart the machine: `sudo systemctl enable docker.service containerd.service`
   2. Add your user to the _docker_ group and restart the machine: `sudo usermod -a -G docker < username >`
7. Setup Skaffold
   1. Download the standalone library, [link](https://skaffold.dev/docs/install/#standalone-binary).
   2. Allow the binary to be executed: `chmod a+x skaffold-<version>-<arch>`
8. Install IntelliJ.
   1. Download the community version of IntelliJ, [link](https://www.jetbrains.com/idea/download).
   2. Extract it: `tar -xvzf idealC-<version>.tar.gz ideaIC-<version>`
   3. Make an _idea_ directory in _/opt_ and copy the extracted contents into it: `sudo mkdir /opt/idea; sudo cp -r ideaIC-<version>/idea-IC-<version>/* /opt/idea`
   4. Link the executible into _/bin_: `sudo ln -sf /opt/idea/bin/idea.sh /bin/intellijidea-ce`
   5. Create a desktop entry for the IDE.
      1. `sudo nano /usr/share/applications/intellij-ce.desktop`

```
[Desktop Entry]
Version=1.0
Type=Application
Name=IntelliJ IDEA Community Edition
Icon=/opt/idea/bin/idea.svg
Exec="/opt/idea/bin/idea.sh" %f
Comment=Capable and Ergonomic IDE for JVM
Categories=Development;IDE;
Terminal=false
StartupWMClass=jetbrains-idea-ce
StartupNotify=true
```

8. Generate a Github Personal Access Token.
   1. Log in to your account.
   2. Go to your account's **Settings**.
   3. Click on **Developer Settings** on the left navigation bar.
   4. Click on **Personal Access Token**.
   5. Click on the **Generate new token** button.
   6. Give the token a descriptive name (e.g., Constellation) and no expiration date. Select all the scopes except for the delete ones. Generate the token.
      ![Generate Personal Access Token](./images/github_token.png)
9. Set your global SBT configurations.
   1. Create the file _~/.sbt/1.0/github.sbt_.
   2. Copy into it below and replace _--- Token ---_ with the Personal Access Token you generated:

```
      credentials +=
      Credentials(
      "Github Package Registry",
      "maven.pkg.github.com",
      "cngo-github",
      "--- Token ---")

githubTokenSource := TokenSource.GitConfig("github.token")
```

10. Create the file _~/.sbt/1.0/plugins/plugins.sbt_ and copy into it: `addSbtPlugin("com.codecommit" % "sbt-github-packages" % "0.5.3")`
11. Clone this repository, [link](https://github.com/Constellation-Labs/tessellation).
12. Open IntelliJ and import the Tessellation repository.
    1. _File -> New -> Project from Existing Sources_
    2. Ensure that the SDK used is Correto Java 8.
       1. _File -> Project Structure -> Project Settings -> Project_
       2. _SDK -> Detected SDKs -> Corretto 8_
    3. Set the language level to 8 as well.
13. Install the Scala SBT plugin.
    1. _File -> Settings -> Plugins -> Scala -> Install_
       ![Install Scala](./images/install_scala.png)
    2. Restart the IDE.
14. Reload the SBT project.
    1. Go to the SBT Window.
       ![Reload the SBT project](./images/reload_project.png)
    2. Right-click on the project -> reload project
15. Build the project: _Build -> Build Project_
