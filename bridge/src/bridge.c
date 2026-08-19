#define _POSIX_C_SOURCE 200809L
#define _XOPEN_SOURCE 600

#include "kab_buffer.h"
#include "kab_config.h"
#include "kab_protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <termios.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#ifdef KAB_APPLE
#include <util.h>
#endif

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

#define KAB_VERSION "0.1.0"
#define KAB_CONNECT_TIMEOUT_SECONDS 2
#define KAB_INITIAL_BACKOFF_MS 250u
#define KAB_MAX_BACKOFF_MS 10000u

struct kab_stats {
    uint64_t pty_rx_bytes;
    uint64_t pty_tx_bytes;
    uint64_t network_rx_bytes;
    uint64_t network_tx_bytes;
    uint64_t read_calls;
    uint64_t write_calls;
    uint64_t connects;
    uint64_t disconnects;
    uint64_t errors;
};

struct kab_session {
    const struct kab_device_config *config;
    int pty_fd;
    int socket_fd;
    int remote_eof;
    char pty_slave[KAB_PATH_SIZE];
    struct kab_buffer to_network;
    struct kab_buffer to_pty;
    struct kab_stats stats;
    uint64_t reconnect_at_ms;
    unsigned backoff_ms;
};

static volatile sig_atomic_t stop_requested;

static void on_signal(int signal_number) {
    (void)signal_number;
    stop_requested = 1;
}

static uint64_t monotonic_ms(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value)) return 0;
    return (uint64_t)value.tv_sec * 1000u + (uint64_t)value.tv_nsec / 1000000u;
}

static void log_line(const char *level, const char *alias, const char *format, ...) {
    struct timespec now;
    clock_gettime(CLOCK_REALTIME, &now);
    fprintf(stderr, "%lld.%03ld %-5s", (long long)now.tv_sec,
            now.tv_nsec / 1000000, level);
    if (alias) fprintf(stderr, " [%s]", alias);
    fputc(' ', stderr);
    va_list arguments;
    va_start(arguments, format);
    vfprintf(stderr, format, arguments);
    va_end(arguments);
    fputc('\n', stderr);
}

static int set_nonblocking(int descriptor) {
    int flags = fcntl(descriptor, F_GETFL);
    return flags < 0 || fcntl(descriptor, F_SETFL, flags | O_NONBLOCK) < 0 ? -1 : 0;
}

static void make_raw(struct termios *attributes) {
    attributes->c_iflag &= (tcflag_t)~(IGNBRK | BRKINT | PARMRK | ISTRIP |
                                       INLCR | IGNCR | ICRNL | IXON);
    attributes->c_oflag &= (tcflag_t)~OPOST;
    attributes->c_lflag &= (tcflag_t)~(ECHO | ECHONL | ICANON | ISIG | IEXTEN);
    attributes->c_cflag &= (tcflag_t)~(CSIZE | PARENB);
    attributes->c_cflag |= CS8;
}

static int write_all(int descriptor, const void *data, size_t length) {
    const uint8_t *cursor = data;
    while (length) {
        ssize_t written = send(descriptor, cursor, length, MSG_NOSIGNAL);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return -1;
        cursor += (size_t)written;
        length -= (size_t)written;
    }
    return 0;
}

static int read_all(int descriptor, void *data, size_t length) {
    uint8_t *cursor = data;
    while (length) {
        ssize_t received = recv(descriptor, cursor, length, 0);
        if (received < 0 && errno == EINTR) continue;
        if (received <= 0) return -1;
        cursor += (size_t)received;
        length -= (size_t)received;
    }
    return 0;
}

static int make_pty(struct kab_session *session) {
    int master = -1;
    int slave = -1;
#ifdef KAB_APPLE
    char name[KAB_PATH_SIZE];
    if (openpty(&master, &slave, name, NULL, NULL)) return -1;
    snprintf(session->pty_slave, sizeof(session->pty_slave), "%s", name);
#else
    master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) || unlockpt(master) ||
        ptsname_r(master, session->pty_slave, sizeof(session->pty_slave))) {
        if (master >= 0) close(master);
        return -1;
    }
    slave = open(session->pty_slave, O_RDWR | O_NOCTTY | O_CLOEXEC);
#endif
    struct termios attributes;
    if (!tcgetattr(master, &attributes)) {
        make_raw(&attributes);
        tcsetattr(master, TCSANOW, &attributes);
    }
    if (slave >= 0) {
        if (!tcgetattr(slave, &attributes)) {
            make_raw(&attributes);
            tcsetattr(slave, TCSANOW, &attributes);
        }
        close(slave);
    }
    if (set_nonblocking(master)) {
        close(master);
        return -1;
    }
    session->pty_fd = master;
    return 0;
}

static int publish_pty(const struct kab_session *session) {
    struct stat status;
    if (!lstat(session->config->pty_link, &status) && !S_ISLNK(status.st_mode)) {
        errno = EEXIST;
        return -1;
    }
    char temporary[KAB_PATH_SIZE + 64];
    int count = snprintf(temporary, sizeof(temporary), "%s.tmp.%ld",
                         session->config->pty_link, (long)getpid());
    if (count < 0 || (size_t)count >= sizeof(temporary)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    unlink(temporary);
    if (symlink(session->pty_slave, temporary)) return -1;
    if (rename(temporary, session->config->pty_link)) {
        int saved = errno;
        unlink(temporary);
        errno = saved;
        return -1;
    }
    return 0;
}

static int configure_socket(int descriptor) {
    int enabled = 1;
    setsockopt(descriptor, IPPROTO_TCP, TCP_NODELAY, &enabled, sizeof(enabled));
    setsockopt(descriptor, SOL_SOCKET, SO_KEEPALIVE, &enabled, sizeof(enabled));
    struct timeval timeout = {.tv_sec = KAB_CONNECT_TIMEOUT_SECONDS, .tv_usec = 0};
    setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(descriptor, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    return 0;
}

static int connect_server(const struct kab_config *config) {
    char port[8];
    snprintf(port, sizeof(port), "%u", config->port);
    struct addrinfo hints = {0};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo *addresses = NULL;
    int lookup = getaddrinfo(config->host, port, &hints, &addresses);
    if (lookup) {
        errno = EHOSTUNREACH;
        return -1;
    }
    int descriptor = -1;
    for (struct addrinfo *address = addresses; address; address = address->ai_next) {
        descriptor = socket(address->ai_family, address->ai_socktype,
                            address->ai_protocol);
        if (descriptor < 0) continue;
        fcntl(descriptor, F_SETFD, FD_CLOEXEC);
        configure_socket(descriptor);
        if (!connect(descriptor, address->ai_addr, address->ai_addrlen)) break;
        close(descriptor);
        descriptor = -1;
    }
    freeaddrinfo(addresses);
    return descriptor;
}

static int open_remote(const struct kab_config *global, struct kab_session *session,
                       char *message, size_t message_size) {
    int descriptor = connect_server(global);
    if (descriptor < 0) return -1;
    struct kab_open_request request = {0};
    memcpy(request.magic, KAB_MAGIC, sizeof(KAB_MAGIC));
    request.version_be = kab_htobe16(KAB_PROTOCOL_VERSION);
    request.operation_be = kab_htobe16(KAB_OP_OPEN);
    request.request_id_be = kab_htobe32((uint32_t)monotonic_ms());
    memcpy(request.token, global->token, sizeof(request.token));
    memcpy(request.device_id, session->config->device_id, sizeof(request.device_id));
    request.baud_be = kab_htobe32(session->config->baud);
    request.data_bits = session->config->data_bits;
    request.stop_bits = session->config->stop_bits;
    request.parity = session->config->parity;
    request.flags = session->config->flags;

    struct kab_response response;
    if (write_all(descriptor, &request, sizeof(request)) ||
        read_all(descriptor, &response, sizeof(response)) ||
        !kab_has_magic(response.magic) ||
        kab_be16toh(response.version_be) != KAB_PROTOCOL_VERSION) {
        close(descriptor);
        errno = EPROTO;
        return -1;
    }
    uint16_t length = kab_be16toh(response.message_length_be);
    if (length > KAB_MAX_MESSAGE_SIZE) {
        close(descriptor);
        errno = EMSGSIZE;
        return -1;
    }
    char incoming[KAB_MAX_MESSAGE_SIZE + 1];
    if (length && read_all(descriptor, incoming, length)) {
        close(descriptor);
        errno = EPROTO;
        return -1;
    }
    incoming[length] = 0;
    uint16_t status = kab_be16toh(response.status_be);
    if (message_size) snprintf(message, message_size, "%s", incoming);
    if (status != KAB_STATUS_OK) {
        close(descriptor);
        errno = EACCES;
        return -(int)status;
    }
    struct timeval no_timeout = {0};
    setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, &no_timeout, sizeof(no_timeout));
    setsockopt(descriptor, SOL_SOCKET, SO_SNDTIMEO, &no_timeout, sizeof(no_timeout));
    if (set_nonblocking(descriptor)) {
        close(descriptor);
        return -1;
    }
    session->socket_fd = descriptor;
    session->remote_eof = 0;
    session->stats.connects++;
    session->backoff_ms = KAB_INITIAL_BACKOFF_MS;
    return 0;
}

static void disconnect_session(struct kab_session *session, const char *reason) {
    if (session->socket_fd >= 0) {
        close(session->socket_fd);
        session->socket_fd = -1;
        session->stats.disconnects++;
        log_line("WARN", session->config->alias, "transport disconnected: %s", reason);
    }
    kab_buffer_clear(&session->to_network);
    kab_buffer_clear(&session->to_pty);
    session->remote_eof = 0;
    session->reconnect_at_ms = monotonic_ms() + session->backoff_ms;
    if (session->backoff_ms < KAB_MAX_BACKOFF_MS) {
        session->backoff_ms *= 2;
        if (session->backoff_ms > KAB_MAX_BACKOFF_MS)
            session->backoff_ms = KAB_MAX_BACKOFF_MS;
    }
}

static int retry_connect(const struct kab_config *global, struct kab_session *session,
                         uint64_t now) {
    if (session->socket_fd >= 0 || now < session->reconnect_at_ms) return 0;
    char message[256] = {0};
    int result = open_remote(global, session, message, sizeof(message));
    if (!result) {
        log_line("INFO", session->config->alias, "connected to USB service");
        return 0;
    }
    session->stats.errors++;
    if (result < -1)
        log_line("ERROR", session->config->alias, "open rejected (%d): %s", -result,
                 message[0] ? message : "no detail");
    session->reconnect_at_ms = now + session->backoff_ms;
    if (session->backoff_ms < KAB_MAX_BACKOFF_MS) {
        session->backoff_ms *= 2;
        if (session->backoff_ms > KAB_MAX_BACKOFF_MS)
            session->backoff_ms = KAB_MAX_BACKOFF_MS;
    }
    return result;
}

static int read_into(int descriptor, struct kab_buffer *buffer, uint64_t *counter,
                     uint64_t *calls) {
    size_t available = kab_buffer_writable(buffer);
    if (!available) return 0;
    ssize_t result = read(descriptor, kab_buffer_write_ptr(buffer), available);
    if (result > 0) {
        kab_buffer_produced(buffer, (size_t)result);
        *counter += (uint64_t)result;
        (*calls)++;
        return 0;
    }
    if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR ||
                       errno == EIO)) return 0;
    return result == 0 ? 1 : -1;
}

static int write_from(int descriptor, struct kab_buffer *buffer, uint64_t *counter,
                      uint64_t *calls, int is_socket) {
    size_t available = kab_buffer_readable(buffer);
    if (!available) return 0;
    ssize_t result = is_socket
        ? send(descriptor, kab_buffer_read_ptr(buffer), available, MSG_NOSIGNAL)
        : write(descriptor, kab_buffer_read_ptr(buffer), available);
    if (result > 0) {
        kab_buffer_consumed(buffer, (size_t)result);
        *counter += (uint64_t)result;
        (*calls)++;
        return 0;
    }
    if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR))
        return 0;
    return -1;
}

static void process_session_events(struct kab_session *session,
                                   short pty_events, short socket_events) {
    if (session->socket_fd < 0) return;
    if (socket_events & (POLLERR | POLLNVAL)) {
        disconnect_session(session, "socket closed");
        return;
    }
    if ((pty_events & POLLIN) &&
        read_into(session->pty_fd, &session->to_network,
                  &session->stats.pty_rx_bytes, &session->stats.read_calls) < 0) {
        disconnect_session(session, "PTY read failed");
        return;
    }
    if ((socket_events & POLLOUT) &&
        write_from(session->socket_fd, &session->to_network,
                   &session->stats.network_tx_bytes, &session->stats.write_calls, 1)) {
        disconnect_session(session, "network write failed");
        return;
    }
    if ((socket_events & POLLIN) && !session->remote_eof) {
        int read_result = read_into(session->socket_fd, &session->to_pty,
                                    &session->stats.network_rx_bytes,
                                    &session->stats.read_calls);
        if (read_result < 0) {
            disconnect_session(session, "network read failed");
            return;
        }
        if (read_result > 0) session->remote_eof = 1;
    }
    if ((pty_events & POLLOUT) &&
        write_from(session->pty_fd, &session->to_pty,
                   &session->stats.pty_tx_bytes, &session->stats.write_calls, 0)) {
        disconnect_session(session, "PTY write failed");
        return;
    }
    if (socket_events & POLLHUP) session->remote_eof = 1;
    if (session->remote_eof && !kab_buffer_readable(&session->to_pty))
        disconnect_session(session, "socket closed");
}

static void print_stats(const struct kab_session *session) {
    log_line("STAT", session->config->alias,
             "connected=%s pty_rx=%llu pty_tx=%llu net_rx=%llu net_tx=%llu "
             "connects=%llu disconnects=%llu errors=%llu",
             session->socket_fd >= 0 ? "yes" : "no",
             (unsigned long long)session->stats.pty_rx_bytes,
             (unsigned long long)session->stats.pty_tx_bytes,
             (unsigned long long)session->stats.network_rx_bytes,
             (unsigned long long)session->stats.network_tx_bytes,
             (unsigned long long)session->stats.connects,
             (unsigned long long)session->stats.disconnects,
             (unsigned long long)session->stats.errors);
}

static int run_bridge(const struct kab_config *config) {
    struct kab_session sessions[KAB_MAX_DEVICES] = {0};
    for (size_t index = 0; index < config->device_count; index++) {
        sessions[index].config = &config->devices[index];
        sessions[index].pty_fd = -1;
        sessions[index].socket_fd = -1;
        sessions[index].backoff_ms = KAB_INITIAL_BACKOFF_MS;
        if (make_pty(&sessions[index]) || publish_pty(&sessions[index])) {
            log_line("ERROR", config->devices[index].alias, "PTY setup failed: %s",
                     strerror(errno));
            return 1;
        }
        log_line("INFO", config->devices[index].alias, "PTY %s -> %s",
                 config->devices[index].pty_link, sessions[index].pty_slave);
    }

    uint64_t next_stats = monotonic_ms() + (uint64_t)config->log_interval_seconds * 1000u;
    while (!stop_requested) {
        uint64_t now = monotonic_ms();
        struct pollfd descriptors[KAB_MAX_DEVICES * 2];
        size_t descriptor_count = 0;
        for (size_t index = 0; index < config->device_count; index++) {
            struct kab_session *session = &sessions[index];
            retry_connect(config, session, now);
            descriptors[descriptor_count++] = (struct pollfd){
                .fd = session->pty_fd,
                .events = session->socket_fd >= 0
                    ? (short)((kab_buffer_writable(&session->to_network) ? POLLIN : 0) |
                              (kab_buffer_readable(&session->to_pty) ? POLLOUT : 0))
                    : 0,
            };
            descriptors[descriptor_count++] = (struct pollfd){
                .fd = session->socket_fd,
                .events = session->socket_fd >= 0
                    ? (short)((!session->remote_eof && kab_buffer_writable(&session->to_pty) ? POLLIN : 0) |
                              (kab_buffer_readable(&session->to_network) ? POLLOUT : 0))
                    : 0,
            };
        }
        int result = poll(descriptors, descriptor_count, 100);
        if (result < 0 && errno != EINTR) {
            log_line("ERROR", NULL, "poll failed: %s", strerror(errno));
            break;
        }
        for (size_t index = 0; index < config->device_count; index++) {
            process_session_events(&sessions[index], descriptors[index * 2].revents,
                                   descriptors[index * 2 + 1].revents);
        }
        now = monotonic_ms();
        if (config->log_interval_seconds && now >= next_stats) {
            for (size_t index = 0; index < config->device_count; index++)
                print_stats(&sessions[index]);
            next_stats = now + (uint64_t)config->log_interval_seconds * 1000u;
        }
    }

    for (size_t index = 0; index < config->device_count; index++) {
        if (sessions[index].socket_fd >= 0) close(sessions[index].socket_fd);
        if (sessions[index].pty_fd >= 0) close(sessions[index].pty_fd);
        struct stat status;
        if (!lstat(sessions[index].config->pty_link, &status) && S_ISLNK(status.st_mode))
            unlink(sessions[index].config->pty_link);
        print_stats(&sessions[index]);
    }
    return 0;
}

static void usage(FILE *output, const char *program) {
    fprintf(output,
            "Usage: %s [--check-config] CONFIG\n"
            "       %s --version\n",
            program, program);
}

int main(int argc, char **argv) {
    int check_only = 0;
    const char *path = NULL;
    for (int index = 1; index < argc; index++) {
        if (!strcmp(argv[index], "--check-config")) check_only = 1;
        else if (!strcmp(argv[index], "--version")) {
            puts("klipper-android-bridge " KAB_VERSION);
            return 0;
        } else if (!strcmp(argv[index], "--help")) {
            usage(stdout, argv[0]);
            return 0;
        } else if (!path) path = argv[index];
        else {
            usage(stderr, argv[0]);
            return 2;
        }
    }
    if (!path) {
        usage(stderr, argv[0]);
        return 2;
    }
    struct kab_config config;
    char error[512];
    if (kab_config_load(path, &config, error, sizeof(error))) {
        fprintf(stderr, "configuration error: %s\n", error);
        return 2;
    }
    if (check_only) {
        printf("configuration ok: %zu device(s), server %s:%u\n",
               config.device_count, config.host, config.port);
        return 0;
    }
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);
    return run_bridge(&config);
}
