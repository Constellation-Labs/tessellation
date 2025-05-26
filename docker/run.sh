#!/usr/bin/env bash

# Enable stricter error handling
set -e

# Function to install 'just' command line tool
install_just() {
  echo "Installing just..."
  
  case "$(uname)" in
    Linux)
      if command -v apt >/dev/null 2>&1; then
        # Ubuntu/Debian
        echo "Detected apt-based system"
        sudo apt install -y just
      elif command -v dnf >/dev/null 2>&1; then
        # Fedora
        echo "Detected dnf-based system"
        sudo dnf install -y just
      elif command -v yum >/dev/null 2>&1; then
        # RHEL/CentOS
        echo "Detected yum-based system"
        if ! command -v curl >/dev/null 2>&1; then
          sudo yum install -y curl
        fi
        curl --proto '=https' --tlsv1.2 -sSf https://just.systems/install.sh | bash -s -- --to /usr/local/bin
      elif command -v pacman >/dev/null 2>&1; then
        # Arch Linux
        echo "Detected pacman-based system"
        sudo pacman -S --noconfirm just
      else
        echo "Unsupported Linux distribution. Installing just using the official installer script..."
        if ! command -v curl >/dev/null 2>&1; then
          echo "curl is required but not installed. Please install curl first."
          exit 1
        fi
        curl --proto '=https' --tlsv1.2 -sSf https://just.systems/install.sh | bash -s -- --to ~/.local/bin
        export PATH="$HOME/.local/bin:$PATH"
      fi
      ;;
    Darwin)
      # macOS
      if command -v brew >/dev/null 2>&1; then
        echo "Installing just using Homebrew..."
        brew install just
      else
        echo "Homebrew not found. Installing Homebrew first..."
        /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
        echo "Installing just using Homebrew..."
        brew install just
      fi
      ;;
    MINGW*|MSYS*|CYGWIN*)
      # Windows
      echo "For Windows, we recommend installing using cargo or the official installer:"
      echo "1. Install using cargo: cargo install just"
      echo "2. Download from: https://github.com/casey/just/releases"
      echo "Installing using Scoop..."
      if command -v scoop >/dev/null 2>&1; then
        scoop install just
      else
        echo "Scoop not found. Please install just manually."
        echo "Visit: https://github.com/casey/just/releases"
        exit 1
      fi
      ;;
    *)
      echo "Unsupported OS: $(uname). Please install just manually."
      echo "Visit: https://github.com/casey/just#packages"
      exit 1
      ;;
  esac
  
  # Verify installation
  if command -v just >/dev/null 2>&1; then
    echo "✅ just successfully installed!"
    just --version
  else
    echo "❌ Failed to install just. Please install it manually."
    echo "Visit: https://github.com/casey/just#packages"
    exit 1
  fi
}

# Check if 'just' is installed, install if not
if ! command -v just >/dev/null 2>&1; then
  echo "'just' command not found. Attempting to install..."
  install_just
else
  echo "✅ just is already installed: $(just --version)"
fi

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Navigate to the project root directory (one level up from the script location)
cd "$SCRIPT_DIR/../"
echo "Changed to project root directory: $(pwd)"

# Execute 'just' with all arguments passed to this script
echo "Running: just $@"
just "$@"
