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
    echo "Never let fail2ban ban the controller"
    # The block above installs AND STARTS fail2ban. fail2ban then reads the
    # EXISTING auth.log, which already contains every SSH attempt the controller
    # made while this box was booting -- wait_ssh (server.py:106) retries every
    # 5s from the moment the server is created, and anything before sshd has the
    # key loaded is a failed auth. Five of those inside 10 minutes is the default
    # sshd jail threshold, so fail2ban's first act can be to ban the controller
    # MID-PROVISIONING: this script dies partway, the runner never registers, and
    # the server is reaped as a zombie. Silent, and it repeats for every job.
    # Observed 2026-09-16 on first deploy.
    #
    # The controller is the one host that must never be banned here, and it is
    # exactly the host on the other end of this SSH session.
    CTRL_IP="${SSH_CLIENT%% *}"
    [ -n "$CTRL_IP" ] || CTRL_IP="${SSH_CONNECTION%% *}"
    if [ -n "$CTRL_IP" ]; then
        mkdir -p /etc/fail2ban/jail.d
        cat > /etc/fail2ban/jail.d/00-controller-allowlist.conf <<CONF
[DEFAULT]
ignoreip = 127.0.0.1/8 ::1 $CTRL_IP
CONF
        systemctl restart fail2ban || true
        # `systemctl restart` returns before the daemon is listening, so calling
        # the client straight away fails with "Failed to access socket path".
        # Harmless (nothing to unban on a clean run) but it prints an error on
        # every single provision, which trains people to ignore the log.
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            fail2ban-client ping >/dev/null 2>&1 && break
            sleep 1
        done
        # Clear anything banned before the allowlist existed.
        fail2ban-client unban --all >/dev/null 2>&1 || true
    else
        echo "WARNING: could not determine controller IP; fail2ban may ban it" >&2
    fi
}

{
    echo "Hosted-runner parity: /usr/local/bin and hostedtoolcache"
    # These are the two places GitHub's hosted images differ from a stock Ubuntu
    # box in ways the workflow silently depends on. Both were already solved in
    # the fixed variant (terraform/templates/runner-init.tpl:78-89); the
    # autoscaled path never got the same treatment, so the first real E2E run on
    # this fleet failed EVERY group in 30 seconds at "Install just" with
    #   cp: cannot create regular file '/usr/local/bin/just': Permission denied
    #
    # e2e-just-test.yml:63 installs just with
    #   curl ... | bash -s -- --to /usr/local/bin
    # and NO sudo, because on a hosted runner the unprivileged user can write
    # there. Group-write rather than chown, so root stays the owner.
    chown root:ubuntu /usr/local/bin
    chmod 2775 /usr/local/bin

    # actions/setup-java and setup-node install here. startup-x64.sh creates it
    # too, but it runs AFTER this script, and pre-creating it costs nothing.
    install -d -m 0775 -o ubuntu -g ubuntu /opt/hostedtoolcache
}

{
    echo "Pre-install nvm for the runner user"
    # docker/bin/install_dependencies.sh gates its Node.js setup on
    # `[ -d "$HOME/.nvm" ]` (check_node) -- it does NOT look for node on PATH.
    # Hosted images ship nvm so that check short-circuits; on a bare box it does
    # not, and `just _check_deps` installs nvm + node mid-job on every single
    # run. Pre-installing restores parity and keeps that cost out of each job.
    sudo -u ubuntu bash -c '
      export NVM_DIR="$HOME/.nvm"
      curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
      . "$NVM_DIR/nvm.sh"
      nvm install 18
      nvm alias default 18
    '
}

{
    echo "Provision swap (OOM backstop)"
    # Hetzner cloud images ship with NO swap. Measured peak for one E2E job is
    # 24 GB of 31.3 GB (77%) across a 12/12 green matrix on 2026-09-17, with swap
    # untouched by every group. (An earlier no-swap ccx33 run reported 29.7 GB /
    # 95%, which does not reproduce with a swapfile present.) Without swap the
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
    # Take the HIGHER of our floor and whatever the image already set. The
    # Hetzner docker-ce image ships vm.max_map_count=1048576, and writing a flat
    # 262144 here silently LOWERS it -- still 4x what ~15 JVMs need, so nothing
    # breaks, but quietly undoing a deliberate image default is the kind of thing
    # that bites much later.
    want_mmc=262144
    cur_mmc=$(sysctl -n vm.max_map_count 2>/dev/null || echo 0)
    [ "$cur_mmc" -gt "$want_mmc" ] && want_mmc="$cur_mmc"
    cat > /etc/sysctl.d/99-ci-runner.conf <<SYSCTL
vm.max_map_count = $want_mmc
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
