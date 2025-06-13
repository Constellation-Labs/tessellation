#!/bin/bash

# Break on any error
set -e


check_java_home() {
    echo "Checking if JAVA_HOME env is set"

  if [ -z "$JAVA_HOME" ] || ! grep -q 'JAVA_HOME' "$HOME/.bashrc"; then
        JAVA_HOME_LINE='export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"'
        echo "JAVA_HOME is not set. Attempting to set automatically"
        echo "Please ensure the following line is in your ~/.bashrc or ~/.zshrc file:"
        echo "Script will attempt to now add it for you and run it, but this only adds to .bashrc" 
        echo $JAVA_HOME_LINE
        echo "$JAVA_HOME_LINE" >> $HOME/.bashrc
        echo "Adding JAVA_HOME to current environment"
        eval $JAVA_HOME_LINE
        echo "JAVA_HOME is now set to: $JAVA_HOME"
    fi
    
}

# Check and install Java 11
check_java() {
  echo "Checking for Java 11..."
  if command -v java >/dev/null 2>&1; then
    java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    echo "Found Java version: $java_version"
    if [[ "$java_version" == 11* ]] || [[ "$java_version" == 1.11* ]]; then
      echo "✅ Java 11 is installed."
      return 0
    else
      echo "⚠️ Java is installed but not version 11. Will attempt to install Java 11."
    fi
  else
    echo "⚠️ Java not found. Will attempt to install Java 11."
  fi

  
  case "$(uname)" in
    Linux)
      if command -v apt >/dev/null 2>&1; then
        echo "Installing Java 11 using apt..."
        sudo apt install -y openjdk-11-jdk
      elif command -v yum >/dev/null 2>&1; then
        echo "Installing Java 11 using yum..."
        sudo yum install -y java-11-openjdk-devel
      else
        echo "⚠️ Unsupported Linux distribution. Please install Java 11 manually."
        return 1
      fi
      ;;
    Darwin)
      if command -v brew >/dev/null 2>&1; then
        echo "Installing Java 11 using Homebrew..."
        brew tap adoptopenjdk/openjdk
        brew install --cask adoptopenjdk11
      else
        echo "⚠️ Homebrew not found. Please install Java 11 manually."
        return 1
      fi
      ;;
    MINGW*|MSYS*|CYGWIN*)
      echo "On Windows, please install Java 11 manually from https://adoptopenjdk.net/"
      return 1
      ;;
    *)
      echo "⚠️ Unsupported OS: $(uname). Please install Java 11 manually."
      return 1
      ;;
  esac
  
  echo "✅ Java 11 installation complete."
  return 0
}

# Check and install sbt
check_sbt() {
  echo "Checking for sbt..."
  if command -v sbt >/dev/null 2>&1; then
    echo "✅ sbt is already installed."
    return 0
  fi
  
  echo "⚠️ sbt not found. Will attempt to install sbt."
  
  case "$(uname)" in
    Linux)
      if command -v apt >/dev/null 2>&1; then
        echo "Installing sbt using apt..."
        echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
        echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | sudo tee /etc/apt/sources.list.d/sbt_old.list
        curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
        sudo apt update
        sudo apt install -y sbt

      elif command -v yum >/dev/null 2>&1; then
        echo "Installing sbt using yum..."
        curl https://www.scala-sbt.org/sbt-rpm.repo | sudo tee /etc/yum.repos.d/scala-sbt-rpm.repo
        sudo yum install -y sbt
      else
        echo "⚠️ Unsupported Linux distribution. Please install sbt manually."
        return 1
      fi
      ;;
    Darwin)
      if command -v brew >/dev/null 2>&1; then
        echo "Installing sbt using Homebrew..."
        brew install sbt
      else
        echo "⚠️ Homebrew not found. Please install sbt manually."
        return 1
      fi
      ;;
    MINGW*|MSYS*|CYGWIN*)
      echo "On Windows, please install sbt manually from https://www.scala-sbt.org/download.html"
      return 1
      ;;
    *)
      echo "⚠️ Unsupported OS: $(uname). Please install sbt manually."
      return 1
      ;;
  esac
  
  echo "✅ sbt installation complete."
  return 0
}

# Check and install jq
check_jq() {
  echo "Checking for jq..."
  if command -v jq >/dev/null 2>&1; then
    echo "✅ jq is already installed."
    return 0
  fi
  
  echo "⚠️ jq not found. Will attempt to install jq."
  
  case "$(uname)" in
    Linux)
      if command -v apt >/dev/null 2>&1; then
        echo "Installing jq using apt..."
        sudo apt install -y jq
      elif command -v yum >/dev/null 2>&1; then
        echo "Installing jq using yum..."
        sudo yum install -y jq
      else
        echo "⚠️ Unsupported Linux distribution. Please install jq manually."
        return 1
      fi
      ;;
    Darwin)
      if command -v brew >/dev/null 2>&1; then
        echo "Installing jq using Homebrew..."
        brew install jq
      else
        echo "⚠️ Homebrew not found. Please install jq manually."
        return 1
      fi
      ;;
    MINGW*|MSYS*|CYGWIN*)
      echo "On Windows, please install jq manually from https://stedolan.github.io/jq/download/"
      return 1
      ;;
    *)
      echo "⚠️ Unsupported OS: $(uname). Please install jq manually."
      return 1
      ;;
  esac
  
  echo "✅ jq installation complete."
  return 0
}

# Check and install wget
check_wget() {
  echo "Checking for wget..."
  if command -v wget >/dev/null 2>&1; then
    echo "✅ wget is already installed."
    return 0
  fi
  
  echo "⚠️ wget not found. Will attempt to install wget."
  
  case "$(uname)" in
    Linux)
      if command -v apt >/dev/null 2>&1; then
        echo "Installing wget using apt..."
        sudo apt update
        sudo apt install -y wget
      elif command -v yum >/dev/null 2>&1; then
        echo "Installing wget using yum..."
        sudo yum install -y wget
      else
        echo "⚠️ Unsupported Linux distribution. Please install wget manually."
        return 1
      fi
      ;;
    Darwin)
      if command -v brew >/dev/null 2>&1; then
        echo "Installing wget using Homebrew..."
        brew install wget
      else
        echo "⚠️ Homebrew not found. Please install wget manually."
        return 1
      fi
      ;;
    MINGW*|MSYS*|CYGWIN*)
      echo "On Windows, please install wget manually."
      return 1
      ;;
    *)
      echo "⚠️ Unsupported OS: $(uname). Please install wget manually."
      return 1
      ;;
  esac
  
  echo "✅ wget installation complete."
  return 0
}

# Check and install curl
check_curl() {
  echo "Checking for curl..."
  if command -v curl >/dev/null 2>&1; then
    echo "✅ curl is already installed."
    return 0
  fi
  
  echo "⚠️ curl not found. Will attempt to install curl."
  
  case "$(uname)" in
    Linux)
      if command -v apt >/dev/null 2>&1; then
        echo "Installing curl using apt..."
        sudo apt update
        sudo apt install -y curl
      elif command -v yum >/dev/null 2>&1; then
        echo "Installing curl using yum..."
        sudo yum install -y curl
      else
        echo "⚠️ Unsupported Linux distribution. Please install curl manually."
        return 1
      fi
      ;;
    Darwin)
      if command -v brew >/dev/null 2>&1; then
        echo "Installing curl using Homebrew..."
        brew install curl
      else
        echo "⚠️ Homebrew not found. Please install curl manually."
        return 1
      fi
      ;;
    MINGW*|MSYS*|CYGWIN*)
      echo "On Windows, please install curl manually."
      return 1
      ;;
    *)
      echo "⚠️ Unsupported OS: $(uname). Please install curl manually."
      return 1
      ;;
  esac
  
  echo "✅ curl installation complete."
  return 0
}

# Check and install Docker Engine
check_docker() {
  echo "Checking for Docker..."
  if command -v docker >/dev/null 2>&1; then
    echo "✅ Docker is already installed."
    return 0
  fi
  
  echo "⚠️ Docker not found. Will attempt to install Docker."
  
  case "$(uname)" in
    Linux)
      if command -v apt >/dev/null 2>&1; then
        echo "Installing Docker using script..."
        curl -fsSL https://get.docker.com -o get-docker.sh
        sudo sh ./get-docker.sh
        sudo usermod -aG docker $USER
        echo "Docker installed. You may need to log out and back in for group changes to take effect."
      else
        echo "⚠️ Unsupported Linux distribution. Please install Docker manually."
        return 1
      fi
      ;;
    Darwin)
        echo "Please install Docker Desktop manually from https://www.docker.com/products/docker-desktop"
        return 1
      ;;
    MINGW*|MSYS*|CYGWIN*)
      echo "On Windows, please install Docker Desktop manually from https://www.docker.com/products/docker-desktop"
      return 1
      ;;
    *)
      echo "⚠️ Unsupported OS: $(uname). Please install Docker manually."
      return 1
      ;;
  esac
  
  echo "✅ Docker installation complete."
  return 0
}

# Check and install Node.js
check_node() {
  echo "Checking for Node.js..."
  if [ -d "$HOME/.nvm" ]; then
    echo "✅ Node.js is already installed."
    return 0
  fi
  
  echo "⚠️ Node.js not found. Will attempt to install Node.js."
  
  case "$(uname)" in
    Linux)
      echo "Installing Node.js using nvm..."
      # NPM
      curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.35.3/install.sh | bash

      # Load nvm without needing to open a new terminal
      export NVM_DIR="$HOME/.nvm"
      [ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"  # This loads nvm
      [ -s "$NVM_DIR/bash_completion" ] && \. "$NVM_DIR/bash_completion"  # This loads nvm bash_completion

      # Install node
      nvm install node

      # Verify installation
      echo "Node version: $(node -v)"
      echo "NPM version: $(npm -v)"
      echo "NPX version: $(npx -v)"
      ;;
    Darwin)
      if command -v brew >/dev/null 2>&1; then
        echo "Installing Node.js using Homebrew..."
        brew install node
      else
        echo "⚠️ Homebrew not found. Please install Node.js manually."
        return 1
      fi
      ;;
    *)
      echo "⚠️ No Node.js installation needed for this OS: $(uname)"
      return 0
      ;;
  esac
  
  echo "✅ Node.js installation complete."
  return 0
}

# Run all checks
echo "🔍 Checking and installing required dependencies..."
check_java
check_java_home
check_sbt
check_jq
check_wget
check_curl
check_docker
check_node

echo "✅ All dependency checks completed."
