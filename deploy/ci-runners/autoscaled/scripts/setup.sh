# Custom runner-server setup script for the tessellation E2E fleet.
#
# Runs as root on each freshly created ephemeral server, BEFORE the actions
# runner is installed (scale_up.py:get_setup_script). The everything-else in the
# scripts folder — startup-x64.sh in particular — is copied verbatim from the
# installed package by bootstrap-controller.sh, so this is the ONLY server-side
# script we own and the only one that drifts from upstream.
#
# WHY THIS EXISTS: `config.scripts` replaces the package's whole scripts
# directory, and get_setup_script()/get_startup_script() raise if a file is
# missing rather than falling back to the package defaults. So overriding setup
# means providing a directory, not a file. The first block below is upstream's
# scripts/setup.sh verbatim; everything after "tessellation additions" is ours.
#
# Both additions exist because a cpx62 runner has 32 GB — the exact memory point
# at which the kernel OOM-killed the Actions runner agent on 2026-08-03 (see
# ../../README.md). On an ephemeral one-job server there is no cleanup hook to
# repair that: the runner agent dies, the job reports `cancelled` with no useful
# log, and the server is reaped. These two knobs are what keep that from being
# the default outcome.

set -x

# ---------------------------------------------------------------------------
# upstream scripts/setup.sh (testflows.github.hetzner.runners) — keep in sync
# ---------------------------------------------------------------------------
{
    echo "Create and configure ubuntu user"
    adduser ubuntu --disabled-password --gecos ""
    echo "%wheel   ALL=(ALL:ALL) NOPASSWD:ALL" >> /etc/sudoers
    addgroup wheel
    addgroup docker
    usermod -aG wheel ubuntu
    usermod -aG sudo ubuntu
    usermod -aG docker ubuntu
}

{
    echo "Install fail2ban"
    apt-get update
    apt-get install --yes --no-install-recommends \
        fail2ban

    echo "Launch fail2ban"
    systemctl start fail2ban
}

# ---------------------------------------------------------------------------
# tessellation additions
# ---------------------------------------------------------------------------
{
    echo "Provision swap (OOM backstop)"
    # Hetzner cloud images ship with NO swap. Measured peak for one E2E job is
    # 29.7 GB of 31.3 GB (95%), with ~10% of samples above 90%. Without swap the
    # kernel does not slow the job down, it OOM-kills a process — and the victim
    # on 2026-08-03 was the runner agent itself.
    #
    # swappiness=10 (default 60) keeps this an emergency backstop: page cache is
    # reclaimed first, anonymous JVM heap only under real pressure. Paged-out
    # heap is slow, but the workflow sets CL_DECLARATION_TIMEOUT /
    # CL_RE_STALL_TIMEOUT generously and a slow job beats a killed runner.
    #
    # No /etc/fstab entry on purpose: this server handles exactly one job and is
    # then deleted, so it never reboots.
    fallocate -l 16G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=16384
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile

    cat > /etc/sysctl.d/99-ci-runner-swap.conf <<'SYSCTL'
vm.swappiness = 10
vm.vfs_cache_pressure = 50
SYSCTL
}

{
    echo "Kernel limits for ~15 concurrent JVMs"
    # vm.max_map_count is the one that actually bites: 15 JVMs plus docker's
    # overlay mounts exhaust the 65530 default and the JVM dies with
    # "Native memory allocation (mmap) failed" long before RAM is exhausted —
    # which reads like a memory bug but is not one.
    #
    # The harness opens many short-lived connections between containers, so the
    # ephemeral port range is widened and TIME_WAIT reuse enabled.
    cat > /etc/sysctl.d/99-ci-runner.conf <<'SYSCTL'
vm.max_map_count = 262144
fs.file-max = 2097152
fs.inotify.max_user_instances = 8192
fs.inotify.max_user_watches = 524288
net.ipv4.ip_local_port_range = 10240 65535
net.ipv4.tcp_tw_reuse = 1
net.core.somaxconn = 4096
SYSCTL

    sysctl --system
}

{
    echo "Report the baseline into the server log"
    # Cheap and worth it: scale-up problems on a shared-vCPU fleet are much
    # easier to diagnose with the box's own view of what it got. `steal` here is
    # the number to watch if consensus tests start flaking.
    nproc
    free -m
    swapon --show
    df -h /
    grep -m1 '^cpu ' /proc/stat
}
