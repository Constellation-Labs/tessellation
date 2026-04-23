#!/usr/bin/env python3
"""Per-process network bytes Prometheus exporter using BPF."""

from bcc import BPF
from prometheus_client import start_http_server, Counter
import time
import signal
import sys

bpf_text = """
#include <uapi/linux/ptrace.h>
#include <net/sock.h>
#include <bcc/proto.h>

struct key_t {
    u32 pid;
    char comm[16];
};

struct sock_key_t {
    u64 pid_tgid;
};

BPF_HASH(send_bytes, struct key_t);
BPF_HASH(recv_bytes, struct key_t);
BPF_HASH(currsock, u64, struct sock *);

// --- TCP send ---
int kprobe__tcp_sendmsg(struct pt_regs *ctx, struct sock *sk) {
    u64 pid_tgid = bpf_get_current_pid_tgid();
    currsock.update(&pid_tgid, &sk);
    return 0;
}

int kretprobe__tcp_sendmsg(struct pt_regs *ctx) {
    int size = PT_REGS_RC(ctx);
    if (size <= 0) return 0;

    u64 pid_tgid = bpf_get_current_pid_tgid();
    struct sock **skpp = currsock.lookup(&pid_tgid);
    if (!skpp) return 0;

    struct sock *sk = *skpp;
    u16 family = sk->__sk_common.skc_family;
    if (family != AF_INET && family != AF_INET6) {
        currsock.delete(&pid_tgid);
        return 0;
    }

    struct key_t key = {};
    key.pid = pid_tgid >> 32;
    bpf_get_current_comm(&key.comm, sizeof(key.comm));

    u64 *val, zero = 0;
    val = send_bytes.lookup_or_try_init(&key, &zero);
    if (val) (*val) += size;

    currsock.delete(&pid_tgid);
    return 0;
}

// --- TCP recv ---
int kprobe__tcp_recvmsg(struct pt_regs *ctx, struct sock *sk) {
    u64 pid_tgid = bpf_get_current_pid_tgid();
    currsock.update(&pid_tgid, &sk);
    return 0;
}

int kretprobe__tcp_recvmsg(struct pt_regs *ctx) {
    int size = PT_REGS_RC(ctx);
    if (size <= 0) return 0;

    u64 pid_tgid = bpf_get_current_pid_tgid();
    struct sock **skpp = currsock.lookup(&pid_tgid);
    if (!skpp) return 0;

    struct sock *sk = *skpp;
    u16 family = sk->__sk_common.skc_family;
    if (family != AF_INET && family != AF_INET6) {
        currsock.delete(&pid_tgid);
        return 0;
    }

    struct key_t key = {};
    key.pid = pid_tgid >> 32;
    bpf_get_current_comm(&key.comm, sizeof(key.comm));

    u64 *val, zero = 0;
    val = recv_bytes.lookup_or_try_init(&key, &zero);
    if (val) (*val) += size;

    currsock.delete(&pid_tgid);
    return 0;
}

// --- UDP send ---
int kprobe__udp_sendmsg(struct pt_regs *ctx, struct sock *sk) {
    u64 pid_tgid = bpf_get_current_pid_tgid();
    currsock.update(&pid_tgid, &sk);
    return 0;
}

int kretprobe__udp_sendmsg(struct pt_regs *ctx) {
    int size = PT_REGS_RC(ctx);
    if (size <= 0) return 0;

    u64 pid_tgid = bpf_get_current_pid_tgid();
    struct sock **skpp = currsock.lookup(&pid_tgid);
    if (!skpp) return 0;

    struct sock *sk = *skpp;
    u16 family = sk->__sk_common.skc_family;
    if (family != AF_INET && family != AF_INET6) {
        currsock.delete(&pid_tgid);
        return 0;
    }

    struct key_t key = {};
    key.pid = pid_tgid >> 32;
    bpf_get_current_comm(&key.comm, sizeof(key.comm));

    u64 *val, zero = 0;
    val = send_bytes.lookup_or_try_init(&key, &zero);
    if (val) (*val) += size;

    currsock.delete(&pid_tgid);
    return 0;
}

// --- UDP recv ---
int kprobe__udp_recvmsg(struct pt_regs *ctx, struct sock *sk) {
    u64 pid_tgid = bpf_get_current_pid_tgid();
    currsock.update(&pid_tgid, &sk);
    return 0;
}

int kretprobe__udp_recvmsg(struct pt_regs *ctx) {
    int size = PT_REGS_RC(ctx);
    if (size <= 0) return 0;

    u64 pid_tgid = bpf_get_current_pid_tgid();
    struct sock **skpp = currsock.lookup(&pid_tgid);
    if (!skpp) return 0;

    struct sock *sk = *skpp;
    u16 family = sk->__sk_common.skc_family;
    if (family != AF_INET && family != AF_INET6) {
        currsock.delete(&pid_tgid);
        return 0;
    }

    struct key_t key = {};
    key.pid = pid_tgid >> 32;
    bpf_get_current_comm(&key.comm, sizeof(key.comm));

    u64 *val, zero = 0;
    val = recv_bytes.lookup_or_try_init(&key, &zero);
    if (val) (*val) += size;

    currsock.delete(&pid_tgid);
    return 0;
}
"""

tx_counter = Counter(
    'process_network_transmit_bytes_total',
    'Total bytes transmitted per process',
    ['pid', 'process_name', 'service']
)
rx_counter = Counter(
    'process_network_receive_bytes_total',
    'Total bytes received per process',
    ['pid', 'process_name', 'service']
)

service_cache = {}

def get_service_name(pid):
    # JAR naming differs between deployments:
    #   mainnet (bare-metal): cl-node.jar, cl-dag-l1.jar, cl-metagraph-*
    #   nightly (docker):     gl0.jar, gl1.jar
    try:
        with open(f"/proc/{pid}/cmdline", 'r') as f:
            cmdline = f.read().replace('\0', ' ')
            if 'cl-node.jar' in cmdline or '/gl0.jar' in cmdline:
                return 'dag-l0'
            elif 'cl-dag-l1.jar' in cmdline or '/gl1.jar' in cmdline:
                return 'dag-l1'
            elif 'cl-metagraph' in cmdline or 'metagraph-l0.jar' in cmdline:
                return 'metagraph-l0'
        with open(f"/proc/{pid}/cgroup", 'r') as f:
            for line in f:
                if '.service' in line:
                    return line.strip().split('/')[-1].replace('.service', '')
    except (FileNotFoundError, PermissionError):
        pass
    return 'unknown'

def resolve_service(pid):
    if pid not in service_cache:
        service_cache[pid] = get_service_name(pid)
    return service_cache[pid]

def collect_and_export(b):
    send_map = b.get_table("send_bytes")
    recv_map = b.get_table("recv_bytes")

    for k, v in send_map.items():
        pid = str(k.pid)
        comm = k.comm.decode('utf-8', errors='replace')
        svc = resolve_service(k.pid)
        tx_counter.labels(pid=pid, process_name=comm, service=svc).inc(v.value)

    for k, v in recv_map.items():
        pid = str(k.pid)
        comm = k.comm.decode('utf-8', errors='replace')
        svc = resolve_service(k.pid)
        rx_counter.labels(pid=pid, process_name=comm, service=svc).inc(v.value)

    send_map.clear()
    recv_map.clear()

def main():
    print("Starting per-process network exporter on :9435")
    b = BPF(text=bpf_text)
    start_http_server(9435)

    def handle_signal(sig, frame):
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    while True:
        try:
            collect_and_export(b)
            time.sleep(5)
        except KeyboardInterrupt:
            break

if __name__ == '__main__':
    main()
