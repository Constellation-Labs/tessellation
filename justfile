# Tessellation Justfile

# Shows help
default:
    @just --list --justfile {{ justfile() }}


# Make sure dependencies are installed before running any recipe
_check_deps:
	@bash docker/bin/install_dependencies.sh

# Main test command to run the Tessellation node setup
# Example: `just test --skip-assembly` to restart without assembling.
# Example: `just test --l1` to re-assemble L0 and L1.
# Example: `just test` to re-assemble L0 only.
test *extra_args:
	@just _check_deps
	@bash docker/bin/compose-runner.sh {{ extra_args }}

purge-docker:
	@bash docker/bin/purge-docker

clean-docker:
	@bash docker/bin/tessellation-docker-cleanup.sh

clean-configs:
	@bash docker/bin/clean-configs.sh

clean:
	@bash sbt clean
	@just clean-configs
	@just clean-docker
