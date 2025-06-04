# Tessellation Justfile

# Shows help
default:
    @just --list --justfile {{ justfile() }}


# Make sure dependencies are installed before running any recipe
_check_deps:
	@bash docker/bin/install_dependencies.sh

# Main test command recompile GL0 & setup docker environment `just test --skip-assembly` to restart without assembling.
test *extra_args:
	@just _check_deps
	@bash docker/bin/compose-runner.sh {{ extra_args }}

# Bring up the default test environment, starting docker images but without running any tests or checks
up *extra_args:
	@just _check_deps
	@bash docker/bin/compose-runner.sh --up {{ extra_args }}

# Destroy test environment, alias for clean-docker
down *extra_args:
	@just clean-docker

# Build the docker images and test environment, without running any containers
build *extra_args:
	@just _check_deps
	@bash docker/bin/compose-runner.sh --build {{ extra_args }}

purge-docker:
	@bash docker/bin/purge-docker.sh

clean-docker:
	@bash docker/bin/tessellation-docker-cleanup.sh

clean-configs:
	@rm -rf ./nodes

clean:
	@bash sbt clean
	@bash cd .github/templates/metagraphs/project_template && sbt clean
	@just clean-configs
	@just clean-docker

debug-main:
	@just _check_deps
	@bash docker/bin/debug/mn-replicate.sh