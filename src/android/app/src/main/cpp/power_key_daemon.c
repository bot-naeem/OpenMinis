#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <time.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/poll.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <android/log.h>

#define LOG_TAG "PowerKeyDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define SOCKET_ABSTRACT_NAME "minis_power_ipc"
#define DEFAULT_LONG_PRESS_MS 600
#define WATCHDOG_TIMEOUT_MS 3000
#define HEARTBEAT_INTERVAL_MS 1000

#define BITS_PER_LONG (sizeof(long) * 8)
#define NBITS(x) ((((x) - 1) / BITS_PER_LONG) + 1)
#define OFF(x) ((x) % BITS_PER_LONG)
#define BIT(x) (1UL << OFF(x))
#define LONG(x) ((x) / BITS_PER_LONG)
#define test_bit(bit, array) ((array[LONG(bit)] >> OFF(bit)) & 1)

static volatile sig_atomic_t g_running = 1;
static int g_event_fd = -1;
static int g_uinput_fd = -1;

static void sig_handler(int sig) {
    (void)sig;
    g_running = 0;
}

static int64_t get_monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ((int64_t)ts.tv_sec * 1000LL) + (ts.tv_nsec / 1000000LL);
}

static void send_ipc_wakeup(void) {
    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        LOGE("Failed to create UNIX stream socket: %s", strerror(errno));
        return;
    }

    struct timeval tv;
    tv.tv_sec = 1;
    tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, SOCKET_ABSTRACT_NAME, sizeof(addr.sun_path) - 2);

    socklen_t addr_len = sizeof(sa_family_t) + 1 + strlen(SOCKET_ABSTRACT_NAME);
    if (connect(sock, (struct sockaddr *)&addr, addr_len) == 0) {
        const char *msg = "WAKEUP\n";
        write(sock, msg, strlen(msg));
        LOGI("Wakeup IPC sent to @%s successfully", SOCKET_ABSTRACT_NAME);
    } else {
        LOGW("Failed to connect to @%s: %s (app might not be listening yet)", SOCKET_ABSTRACT_NAME, strerror(errno));
    }
    close(sock);

    // Acquire wake lock via sysfs if accessible
    int wl_fd = open("/sys/power/wake_lock", O_WRONLY);
    if (wl_fd >= 0) {
        const char *wl_name = "minis_power_wake";
        write(wl_fd, wl_name, strlen(wl_name));
        close(wl_fd);
    }
}

static int find_power_key_device(char *out_path, size_t max_len) {
    char dev_path[64];
    unsigned long key_bits[NBITS(KEY_CNT)];
    char name[128];

    for (int i = 0; i < 64; i++) {
        snprintf(dev_path, sizeof(dev_path), "/dev/input/event%d", i);
        int fd = open(dev_path, O_RDONLY);
        if (fd < 0) continue;

        memset(key_bits, 0, sizeof(key_bits));
        memset(name, 0, sizeof(name));

        if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits) >= 0) {
            if (test_bit(KEY_POWER, key_bits)) {
                ioctl(fd, EVIOCGNAME(sizeof(name)), name);
                close(fd);
                LOGI("Found power key on device %s ('%s')", dev_path, name);
                snprintf(out_path, max_len, "%s", dev_path);
                return 0;
            }
        }
        close(fd);
    }
    LOGE("Could not find any input device with KEY_POWER");
    return -1;
}

static int create_uinput_clone(int source_event_fd) {
    int uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (uinput_fd < 0) {
        uinput_fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    }
    if (uinput_fd < 0) {
        return -1;
    }

    ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(uinput_fd, UI_SET_EVBIT, EV_SYN);

    unsigned long key_bits[NBITS(KEY_CNT)];
    memset(key_bits, 0, sizeof(key_bits));
    if (ioctl(source_event_fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits) >= 0) {
        for (int k = 0; k < KEY_CNT; k++) {
            if (test_bit(k, key_bits)) {
                ioctl(uinput_fd, UI_SET_KEYBIT, k);
            }
        }
    } else {
        ioctl(uinput_fd, UI_SET_KEYBIT, KEY_POWER);
        ioctl(uinput_fd, UI_SET_KEYBIT, KEY_VOLUMEUP);
        ioctl(uinput_fd, UI_SET_KEYBIT, KEY_VOLUMEDOWN);
    }

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_HOST;
    usetup.id.vendor = 0x1234;
    usetup.id.product = 0x5678;
    strcpy(usetup.name, "minis_virtual_key_pass");

    if (ioctl(uinput_fd, UI_DEV_SETUP, &usetup) < 0) {
        close(uinput_fd);
        return -1;
    }
    if (ioctl(uinput_fd, UI_DEV_CREATE) < 0) {
        close(uinput_fd);
        return -1;
    }

    return uinput_fd;
}

static void emit_event(int fd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    write(fd, &ev, sizeof(ev));
}

static void emit_syn(int fd) {
    emit_event(fd, EV_SYN, SYN_REPORT, 0);
}

static void worker_loop(int event_fd, int uinput_fd, int heartbeat_pipe_fd, int long_press_threshold_ms) {
    g_event_fd = event_fd;
    g_uinput_fd = uinput_fd;

    if (ioctl(event_fd, EVIOCGRAB, 1) < 0) {
        LOGE("Failed to grab event device: %s", strerror(errno));
        return;
    }
    LOGI("Successfully grabbed event device with EVIOCGRAB!");

    struct pollfd pfd;
    pfd.fd = event_fd;
    pfd.events = POLLIN;

    int64_t power_down_time = 0;
    bool power_held = false;
    bool long_press_triggered = false;
    int64_t last_heartbeat = get_monotonic_ms();

    while (g_running) {
        int64_t now = get_monotonic_ms();

        // Send heartbeat to supervisor
        if (now - last_heartbeat >= HEARTBEAT_INTERVAL_MS) {
            char hb = 'H';
            if (write(heartbeat_pipe_fd, &hb, 1) <= 0) {
                // Pipe broken or closed by parent
                break;
            }
            last_heartbeat = now;
        }

        // Determine poll timeout
        int timeout_ms = 50;
        if (power_held && !long_press_triggered) {
            int64_t remaining = (power_down_time + long_press_threshold_ms) - now;
            if (remaining <= 0) {
                // Long-press threshold reached!
                long_press_triggered = true;
                send_ipc_wakeup();
                timeout_ms = 50;
            } else if (remaining < timeout_ms) {
                timeout_ms = (int)remaining;
            }
        }

        int ret = poll(&pfd, 1, timeout_ms);
        if (ret < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (ret > 0 && (pfd.revents & POLLIN)) {
            struct input_event ev;
            ssize_t bytes = read(event_fd, &ev, sizeof(ev));
            if (bytes == sizeof(ev)) {
                if (ev.type == EV_KEY && ev.code == KEY_POWER) {
                    if (ev.value == 1) { // Pressed down
                        power_down_time = get_monotonic_ms();
                        power_held = true;
                        long_press_triggered = false;
                    } else if (ev.value == 0) { // Released
                        if (power_held) {
                            power_held = false;
                            if (!long_press_triggered) {
                                // Short press! Replay to uinput
                                emit_event(uinput_fd, EV_KEY, KEY_POWER, 1);
                                emit_syn(uinput_fd);
                                emit_event(uinput_fd, EV_KEY, KEY_POWER, 0);
                                emit_syn(uinput_fd);
                            }
                            // If long_press_triggered, drop the release event
                        }
                    }
                } else {
                    // Forward all other events (volume keys, syn, etc.) to uinput directly
                    write(uinput_fd, &ev, sizeof(ev));
                }
            }
        }
    }

    // Clean up grab on exit
    ioctl(event_fd, EVIOCGRAB, 0);
}

int main(int argc, char **argv) {
    int long_press_threshold_ms = DEFAULT_LONG_PRESS_MS;
    if (argc > 1) {
        int val = atoi(argv[1]);
        if (val >= 200 && val <= 3000) {
            long_press_threshold_ms = val;
        }
    }

    char event_dev[64];
    if (find_power_key_device(event_dev, sizeof(event_dev)) < 0) {
        fprintf(stderr, "No device with KEY_POWER found\n");
        return 1;
    }

    signal(SIGINT, sig_handler);
    signal(SIGTERM, sig_handler);

    // Supervisor / Worker architecture
    while (g_running) {
        int pipe_fds[2];
        if (pipe(pipe_fds) < 0) {
            sleep(1);
            continue;
        }

        pid_t pid = fork();
        if (pid < 0) {
            close(pipe_fds[0]);
            close(pipe_fds[1]);
            sleep(1);
            continue;
        }

        if (pid == 0) {
            // Child: Worker process
            close(pipe_fds[0]); // Close read end

            int ev_fd = open(event_dev, O_RDONLY);
            if (ev_fd < 0) {
                exit(1);
            }
            int uinp_fd = create_uinput_clone(ev_fd);
            if (uinp_fd < 0) {
                close(ev_fd);
                exit(1);
            }

            worker_loop(ev_fd, uinp_fd, pipe_fds[1], long_press_threshold_ms);

            close(pipe_fds[1]);
            close(uinp_fd);
            close(ev_fd);
            exit(0);
        } else {
            // Parent: Supervisor Watchdog
            close(pipe_fds[1]); // Close write end

            struct pollfd pfd;
            pfd.fd = pipe_fds[0];
            pfd.events = POLLIN;

            while (g_running) {
                int ret = poll(&pfd, 1, WATCHDOG_TIMEOUT_MS);
                if (ret == 0) {
                    // Watchdog timeout! Worker is stuck/deadlocked
                    fprintf(stderr, "[Watchdog] Worker pid %d timed out. Killing...\n", pid);
                    kill(pid, SIGKILL);
                    break;
                } else if (ret < 0) {
                    if (errno == EINTR) continue;
                    break;
                } else {
                    char buf[16];
                    ssize_t n = read(pipe_fds[0], buf, sizeof(buf));
                    if (n <= 0) {
                        // Worker closed pipe or exited
                        break;
                    }
                }
            }

            close(pipe_fds[0]);
            int status = 0;
            waitpid(pid, &status, 0);

            if (!g_running) break;
            usleep(200000); // 200ms rest before respawn
        }
    }

    return 0;
}
