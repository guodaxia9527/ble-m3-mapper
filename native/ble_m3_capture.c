/*
 * BLE-M3 diagnostic input capture for Android.
 *
 * Independently implemented from the observed EV_REL / BTN_LEFT event stream.
 * The EVIOCGRAB approach is also used by the public BLE-M3 Android Interceptor
 * project. No source code from that project is included here. See PROVENANCE.md.
 *
 * This program never injects input, runs shell commands, or changes device
 * permissions. It only grabs the two uniquely named BLE-M3 input interfaces.
 * Closing its descriptor (including on process exit) releases the kernel grab.
 */

#define _POSIX_C_SOURCE 200809L

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <time.h>
#include <unistd.h>

static const char *device_sets[][2] = {
    {"BLE-M3 Mouse", "BLE-M3 Consumer Control"},
    {"Beauty-R1", "Beauty-R1 Consumer Control"}
};
#define DEFAULT_SECONDS 180
#define MAX_SECONDS 600
#define MOVEMENT_THRESHOLD 20

static volatile sig_atomic_t stop_signal;

struct gesture {
    bool pressed;
    bool resync;
    int64_t dx;
    int64_t dy;
    bool photo;
    bool pending;
    int64_t pressed_us;
    int64_t released_us;
    int64_t consumer_us;
    int64_t pending_until_ms;
    bool have_x;
    bool have_y;
    int last_x;
    int last_y;
};

static void request_stop(int signal_number) {
    stop_signal = signal_number;
}

static int install_signal_handlers(void) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = request_stop;
    sigemptyset(&action.sa_mask);
    const int signals[] = {SIGINT, SIGTERM, SIGHUP, SIGPIPE};
    for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); ++i) {
        if (sigaction(signals[i], &action, NULL) != 0) {
            perror("sigaction");
            return -1;
        }
    }
    return 0;
}

static int64_t monotonic_ms(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) {
        return -1;
    }
    return (int64_t)value.tv_sec * 1000 + value.tv_nsec / 1000000;
}

/* No input is read from other devices; only their kernel names are queried. */
static int open_target(char *selected_path, size_t selected_path_size, const char *target_name, bool quiet) {
    DIR *directory = opendir("/dev/input");
    if (directory == NULL) {
        perror("open /dev/input");
        return -1;
    }
    int selected_fd = -1;
    bool duplicate = false;
    struct dirent *entry;
    while (!stop_signal && (entry = readdir(directory)) != NULL) {
        if (strncmp(entry->d_name, "event", 5) != 0) {
            continue;
        }
        char path[PATH_MAX];
        int length = snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        if (length < 0 || (size_t)length >= sizeof(path)) {
            continue;
        }
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) {
            continue;
        }
        char name[256] = {0};
        int result = ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);
        if (result >= 0 && strcmp(name, target_name) == 0) {
            if (selected_fd >= 0) {
                duplicate = true;
                close(fd);
                break;
            }
            selected_fd = fd;
            snprintf(selected_path, selected_path_size, "%s", path);
        } else {
            close(fd);
        }
    }
    closedir(directory);
    if (duplicate || stop_signal) {
        if (selected_fd >= 0) {
            close(selected_fd);
        }
        if (duplicate) {
            fprintf(stderr, "ERROR multiple devices named %s; refusing to grab\n", target_name);
        }
        return -1;
    }
    if (selected_fd < 0 && !quiet) {
        fprintf(stderr, "ERROR no readable input device named %s; check Bluetooth and adb-shell access\n", target_name);
    }
    return selected_fd;
}

static int open_key_target(char *selected_path, size_t selected_path_size, const char *target_name,
                           const int key_codes[6]) {
    DIR *directory = opendir("/dev/input");
    if (directory == NULL) return -1;
    const size_t word_bits = sizeof(unsigned long) * 8;
    unsigned long key_bits[(KEY_MAX + word_bits) / word_bits];
    int selected_fd = -1;
    bool duplicate = false;
    struct dirent *entry;
    while (!stop_signal && (entry = readdir(directory)) != NULL) {
        if (strncmp(entry->d_name, "event", 5) != 0) continue;
        char path[PATH_MAX];
        int length = snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        if (length < 0 || (size_t)length >= sizeof(path)) continue;
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        char name[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0 || strcmp(name, target_name) != 0) {
            close(fd);
            continue;
        }
        memset(key_bits, 0, sizeof(key_bits));
        if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits) < 0) {
            close(fd);
            continue;
        }
        bool supports_all = true;
        for (int i = 0; i < 6; ++i) {
            unsigned int code = (unsigned int)key_codes[i];
            if ((key_bits[code / word_bits] & (1UL << (code % word_bits))) == 0) {
                supports_all = false;
                break;
            }
        }
        if (!supports_all) {
            close(fd);
            continue;
        }
        if (selected_fd >= 0) {
            duplicate = true;
            close(fd);
            break;
        }
        selected_fd = fd;
        snprintf(selected_path, selected_path_size, "%s", path);
    }
    closedir(directory);
    if (duplicate || stop_signal) {
        if (selected_fd >= 0) close(selected_fd);
        if (duplicate) fprintf(stderr, "ERROR multiple matching key interfaces named %s\n", target_name);
        return -1;
    }
    return selected_fd;
}

static int64_t event_us(const struct input_event *event) {
    return (int64_t)event->time.tv_sec * 1000000 + event->time.tv_usec;
}

static void finish_gesture(struct gesture *gesture) {
    if (!gesture->pending) return;
    int64_t ax = llabs(gesture->dx), ay = llabs(gesture->dy);
    const char *action;
    if (ax < MOVEMENT_THRESHOLD && ay < MOVEMENT_THRESHOLD) {
        action = gesture->photo ? "PHOTO" : "CENTER";
    } else if (ay >= MOVEMENT_THRESHOLD && ay >= ax) {
        action = gesture->dy > 0 ? "UP" : "DOWN";
    } else {
        action = gesture->dx > 0 ? "LEFT" : "RIGHT";
    }
    printf("ACTION %s dx=%" PRId64 " dy=%" PRId64 "\n", action, gesture->dx, gesture->dy);
    gesture->pending = false;
}

static void finish_before_disconnect(struct gesture *gesture) {
    /* A released directional gesture is complete even if Bluetooth drops now.
       A plain click might still be missing its Consumer Control half. */
    if (gesture->pending && (gesture->photo || llabs(gesture->dx) >= MOVEMENT_THRESHOLD ||
                             llabs(gesture->dy) >= MOVEMENT_THRESHOLD)) finish_gesture(gesture);
}

static void process_event(struct gesture *gesture, int source, const struct input_event *event,
                          int64_t now_ms, bool beauty) {
    if (event->type == EV_SYN && event->code == SYN_DROPPED) {
        memset(gesture, 0, sizeof(*gesture));
        gesture->resync = true;
        fprintf(stderr, "WARNING input queue overflow; discarded partial gesture\n");
        return;
    }
    if (gesture->resync) {
        if (event->type == EV_SYN && event->code == SYN_REPORT) {
            gesture->resync = false;
        }
        return;
    }
    int64_t stamp = event_us(event);
    if (source == 1) {
        if (event->type == EV_KEY && event->value == 1 &&
            (event->code == KEY_VOLUMEUP || event->code == KEY_VOLUMEDOWN)) {
            gesture->consumer_us = stamp;
            if ((gesture->pressed || gesture->pending) && stamp >= gesture->pressed_us - 2000 &&
                (gesture->pressed || stamp <= gesture->released_us + 2000)) {
                gesture->photo = true;
            }
        }
        return;
    }
    int touch_button = beauty ? BTN_TOUCH : BTN_LEFT;
    if (event->type == EV_KEY && event->code == touch_button) {
        if (event->value == 1 && !gesture->pressed) {
            finish_gesture(gesture);
            gesture->pressed = true;
            gesture->dx = 0;
            gesture->dy = 0;
            gesture->pressed_us = stamp;
            gesture->photo = gesture->consumer_us >= stamp - 2000 && gesture->consumer_us <= stamp + 150000;
            gesture->have_x = false;
            gesture->have_y = false;
        } else if (event->value == 0 && gesture->pressed) {
            gesture->pressed = false;
            gesture->released_us = stamp;
            gesture->pending = true;
            /* Allow the second interface's already-timestamped report to arrive. */
            gesture->pending_until_ms = now_ms + 75;
        }
    } else if (!beauty && event->type == EV_REL && gesture->pressed) {
        if (event->code == REL_X) {
            gesture->dx += event->value;
        } else if (event->code == REL_Y) {
            gesture->dy += event->value;
        }
    } else if (beauty && event->type == EV_ABS && gesture->pressed) {
        if (event->code == ABS_X) {
            if (gesture->have_x) gesture->dx += (int64_t)event->value - gesture->last_x;
            gesture->last_x = event->value;
            gesture->have_x = true;
        } else if (event->code == ABS_Y) {
            if (gesture->have_y) gesture->dy += (int64_t)event->value - gesture->last_y;
            gesture->last_y = event->value;
            gesture->have_y = true;
        }
    }
}

struct tagged_event { int source; struct input_event event; };
static int by_timestamp(const void *left, const void *right) {
    const struct tagged_event *a = left, *b = right;
    int64_t at = event_us(&a->event), bt = event_us(&b->event);
    return at < bt ? -1 : at > bt ? 1 : a->source - b->source;
}

static int replay(const char *path, bool beauty) {
    FILE *file = fopen(path, "r");
    if (!file) { perror("replay"); return 1; }
    char line[512], source[32];
    struct gesture gesture = {0};
    int64_t latest_ms = 0;
    while (fgets(line, sizeof(line), file)) {
        long long sec, usec;
        unsigned int type, code;
        int value;
        if (sscanf(line, "EVENT source=%31s t=%lld.%lld type=%u code=%u value=%d",
                   source, &sec, &usec, &type, &code, &value) != 6) {
            if (strncmp(line, "DISCONNECTED", 12) == 0) {
                finish_before_disconnect(&gesture);
                memset(&gesture, 0, sizeof(gesture));
            }
            continue;
        }
        struct input_event event = {0};
        event.time.tv_sec = sec; event.time.tv_usec = usec;
        event.type = type; event.code = code; event.value = value;
        int64_t now_ms = event_us(&event) / 1000;
        if (now_ms > latest_ms) latest_ms = now_ms;
        if (gesture.pending && latest_ms >= gesture.pending_until_ms) finish_gesture(&gesture);
        process_event(&gesture, strcmp(source, "consumer") == 0, &event, latest_ms, beauty);
    }
    finish_gesture(&gesture);
    fclose(file);
    return 0;
}

static int listen_events(int *fds, int64_t deadline, bool beauty) {
    struct gesture gesture = {0};
    struct pollfd descriptors[2] = {{.fd=fds[0], .events=POLLIN}, {.fd=fds[1], .events=POLLIN}};
    printf("LISTENER_READY six_buttons=true threshold=%d\n", MOVEMENT_THRESHOLD);

    while (!stop_signal) {
        int64_t now = monotonic_ms();
        if (now < 0) {
            perror("clock_gettime");
            return 1;
        }
        if (now >= deadline) {
            finish_gesture(&gesture);
            printf("TIMEOUT\n");
            return 0;
        }
        if (gesture.pending && now >= gesture.pending_until_ms) finish_gesture(&gesture);
        int ready = poll(descriptors, 2, 25);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            perror("poll input");
            return 1;
        }
        if (ready == 0) {
            continue;
        }
        struct tagged_event batch[128];
        size_t count = 0;
        for (int source = 0; source < 2; ++source) {
            if (descriptors[source].revents & (POLLHUP | POLLERR | POLLNVAL)) {
                printf("DEVICE_DISCONNECTED source=%d\n", source);
                finish_before_disconnect(&gesture);
                return 4;
            }
            for (int n = 0; n < 64; ++n) {
                struct input_event event;
                ssize_t bytes = read(fds[source], &event, sizeof(event));
                if (bytes < 0 && (errno == EAGAIN || errno == EINTR)) break;
                if (bytes != sizeof(event)) {
                    printf("DEVICE_READ_INTERRUPTED source=%d\n", source);
                    finish_before_disconnect(&gesture);
                    return 4;
                }
                batch[count++] = (struct tagged_event){.source=source, .event=event};
            }
        }
        qsort(batch, count, sizeof(batch[0]), by_timestamp);
        for (size_t i = 0; i < count; ++i)
            process_event(&gesture, batch[i].source, &batch[i].event, now, beauty);
    }
    fprintf(stderr, "STOP signal=%d\n", (int)stop_signal);
    return 0;
}

static int key_button_index(const int key_codes[6], int code) {
    for (int i = 0; i < 6; ++i) if (key_codes[i] == code) return i;
    return -1;
}

static bool input_has_code(int fd, unsigned int type, unsigned int code) {
    const size_t word_bits = sizeof(unsigned long) * 8;
    unsigned long bits[(KEY_MAX + word_bits) / word_bits];
    memset(bits, 0, sizeof(bits));
    if (ioctl(fd, EVIOCGBIT(type, sizeof(bits)), bits) < 0) return false;
    return (bits[code / word_bits] & (1UL << (code % word_bits))) != 0;
}

/* Returns 1 for absolute touch, 0 for relative mouse, and -1 if unsupported. */
static int detect_gesture_protocol(int fd) {
    if (input_has_code(fd, EV_ABS, ABS_X) && input_has_code(fd, EV_ABS, ABS_Y) &&
            input_has_code(fd, EV_KEY, BTN_TOUCH)) return 1;
    if (input_has_code(fd, EV_REL, REL_X) && input_has_code(fd, EV_REL, REL_Y) &&
            input_has_code(fd, EV_KEY, BTN_LEFT)) return 0;
    return -1;
}

static int listen_named_gesture(const char *device_name, const char *consumer_name,
                                int requested_protocol, int seconds) {
    int64_t start = monotonic_ms();
    if (start < 0) return 1;
    int64_t deadline = seconds == 0 ? INT64_MAX : start + seconds * 1000LL;
    bool waiting_logged = false;
    while (!stop_signal && monotonic_ms() < deadline) {
        int fds[2] = {-1, -1};
        char path[PATH_MAX] = {0};
        fds[0] = open_target(path, sizeof(path), device_name, true);
        if (fds[0] >= 0 && consumer_name != NULL && consumer_name[0] != '\0')
            fds[1] = open_target(path, sizeof(path), consumer_name, true);
        if (fds[0] < 0 || (consumer_name != NULL && consumer_name[0] != '\0' && fds[1] < 0)) {
            for (int i = 0; i < 2; ++i) if (fds[i] >= 0) close(fds[i]);
            if (!waiting_logged) { printf("WAITING_FOR_DEVICE\n"); waiting_logged = true; }
            struct timespec delay = {.tv_sec=0, .tv_nsec=200000000};
            nanosleep(&delay, NULL);
            continue;
        }
        int detected_protocol = requested_protocol >= 0 ? requested_protocol : detect_gesture_protocol(fds[0]);
        if (detected_protocol < 0) {
            fprintf(stderr, "ERROR selected device does not expose a supported mouse/touch gesture interface: %s\n", device_name);
            for (int i = 0; i < 2; ++i) if (fds[i] >= 0) close(fds[i]);
            return 2;
        }
        bool absolute = detected_protocol == 1;
        int result = 0;
        bool grabbed[2] = {false, false};
        for (int i = 0; i < 2; ++i) {
            if (fds[i] < 0) continue;
            if (ioctl(fds[i], EVIOCGRAB, 1) != 0) {
                fprintf(stderr, "GRAB_FAILED %s errno=%d message=%s\n",
                        i == 0 ? device_name : consumer_name, errno, strerror(errno));
                result = 3;
                break;
            }
            grabbed[i] = true;
            printf("GRAB_OK %s\n", i == 0 ? device_name : consumer_name);
        }
        printf("REMOTE_PROFILE LearnedGesture protocol=%s\n", absolute ? "absolute" : "relative");
        waiting_logged = false;
        if (result == 0) result = listen_events(fds, deadline, absolute);
        for (int i = 0; i < 2; ++i) {
            if (grabbed[i]) ioctl(fds[i], EVIOCGRAB, 0);
            if (fds[i] >= 0) close(fds[i]);
        }
        printf("RELEASED\n");
        if (result != 4) return result;
    }
    printf("STOPPED\n");
    return 0;
}

static int listen_learned_keys(const char *device_name, const int key_codes[6], int seconds) {
    static const char *actions[] = {"UP", "DOWN", "LEFT", "RIGHT", "CENTER", "PHOTO"};
    int64_t start = monotonic_ms();
    if (start < 0) return 1;
    int64_t deadline = seconds == 0 ? INT64_MAX : start + seconds * 1000LL;
    bool waiting_logged = false;
    while (!stop_signal && monotonic_ms() < deadline) {
        char path[PATH_MAX] = {0};
        int fd = open_key_target(path, sizeof(path), device_name, key_codes);
        if (fd < 0) {
            if (!waiting_logged) { printf("WAITING_FOR_DEVICE\n"); waiting_logged = true; }
            struct timespec delay = {.tv_sec=0, .tv_nsec=200000000};
            nanosleep(&delay, NULL);
            continue;
        }
        if (ioctl(fd, EVIOCGRAB, 1) != 0) {
            fprintf(stderr, "GRAB_FAILED %s errno=%d message=%s\n", device_name, errno, strerror(errno));
            close(fd);
            return 3;
        }
        printf("GRAB_OK %s\nREMOTE_PROFILE LearnedKeyboard\nLISTENER_READY six_buttons=true\n", device_name);
        waiting_logged = false;
        bool disconnected = false;
        while (!stop_signal && monotonic_ms() < deadline) {
            struct pollfd descriptor = {.fd=fd, .events=POLLIN};
            int timeout = 200;
            if (deadline != INT64_MAX) {
                int64_t remaining = deadline - monotonic_ms();
                if (remaining <= 0) break;
                if (remaining < timeout) timeout = (int)remaining;
            }
            int ready = poll(&descriptor, 1, timeout);
            if (ready < 0) {
                if (errno == EINTR) continue;
                printf("DEVICE_READ_INTERRUPTED\n");
                disconnected = true;
                break;
            }
            if (ready == 0) continue;
            if (descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) {
                printf("DEVICE_DISCONNECTED\n");
                disconnected = true;
                break;
            }
            struct input_event events[32];
            ssize_t bytes = read(fd, events, sizeof(events));
            if (bytes < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            if (bytes <= 0 || bytes % (ssize_t)sizeof(events[0]) != 0) {
                printf("DEVICE_DISCONNECTED\n");
                disconnected = true;
                break;
            }
            size_t count = (size_t)bytes / sizeof(events[0]);
            for (size_t i = 0; i < count; ++i) {
                if (events[i].type != EV_KEY || events[i].value != 1) continue;
                int index = key_button_index(key_codes, events[i].code);
                if (index >= 0) printf("ACTION %s dx=0 dy=0\n", actions[index]);
            }
        }
        ioctl(fd, EVIOCGRAB, 0);
        close(fd);
        printf("RELEASED\n");
        if (!disconnected || stop_signal || monotonic_ms() >= deadline) break;
    }
    printf("STOPPED\n");
    return 0;
}

static bool parse_key_code(const char *value, int *out) {
    char *end = NULL;
    errno = 0;
    long code = strtol(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || code < 1 || code > 767) return false;
    *out = (int)code;
    return true;
}

static void usage(const char *program) {
    fprintf(stderr, "Usage: %s [--probe | --listen] [--seconds 0..%d]\n", program, MAX_SECONDS);
    fprintf(stderr, "       %s --listen-keys --device NAME and six --key-* CODE options\n", program);
    fprintf(stderr, "       %s --listen-gesture --device NAME [--consumer NAME] --protocol auto|rel|abs\n", program);
    fprintf(stderr, "Supports BLE-M3 and Beauty-R1 input profiles.\n");
    fprintf(stderr, "--seconds 0 listens until stopped; exits when its parent dies.\n");
    fprintf(stderr, "Default: --listen --seconds %d. No input is injected.\n", DEFAULT_SECONDS);
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc == 3 && strcmp(argv[1], "--replay") == 0) return replay(argv[2], false);
    if (argc == 3 && strcmp(argv[1], "--replay-beauty") == 0) return replay(argv[2], true);
    bool probe = false;
    bool explicit_mode = false;
    bool learned_keys_mode = false;
    bool learned_gesture_mode = false;
    const char *learned_consumer = NULL;
    int learned_protocol = -1;
    bool protocol_set = false;
    const char *learned_device = NULL;
    int learned_codes[6] = {0};
    bool ble_m3_enabled = true;
    bool beauty_r1_enabled = true;
    int seconds = DEFAULT_SECONDS;
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--probe") == 0 || strcmp(argv[i], "--listen") == 0 ||
                strcmp(argv[i], "--listen-keys") == 0 || strcmp(argv[i], "--listen-gesture") == 0) {
            if (explicit_mode) {
                usage(argv[0]);
                return 2;
            }
            explicit_mode = true;
            probe = strcmp(argv[i], "--probe") == 0;
            learned_keys_mode = strcmp(argv[i], "--listen-keys") == 0;
            learned_gesture_mode = strcmp(argv[i], "--listen-gesture") == 0;
        } else if (strcmp(argv[i], "--device") == 0 && i + 1 < argc) {
            learned_device = argv[++i];
        } else if (strcmp(argv[i], "--consumer") == 0 && i + 1 < argc) {
            learned_consumer = argv[++i];
        } else if (strcmp(argv[i], "--protocol") == 0 && i + 1 < argc) {
            const char *protocol = argv[++i];
            protocol_set = true;
            if (strcmp(protocol, "auto") == 0) learned_protocol = -1;
            else if (strcmp(protocol, "abs") == 0) learned_protocol = 1;
            else if (strcmp(protocol, "rel") == 0) learned_protocol = 0;
            else { usage(argv[0]); return 2; }
        } else if (strcmp(argv[i], "--disable-ble-m3") == 0) {
            ble_m3_enabled = false;
        } else if (strcmp(argv[i], "--disable-beauty-r1") == 0) {
            beauty_r1_enabled = false;
        } else if (strncmp(argv[i], "--key-", 6) == 0 && i + 1 < argc) {
            static const char *button_names[] = {"--key-up", "--key-down", "--key-left", "--key-right", "--key-center", "--key-photo"};
            int found = -1;
            for (int button = 0; button < 6; ++button)
                if (strcmp(argv[i], button_names[button]) == 0) found = button;
            if (found < 0 || !parse_key_code(argv[++i], &learned_codes[found])) {
                usage(argv[0]);
                return 2;
            }
        } else if (strcmp(argv[i], "--seconds") == 0 && i + 1 < argc) {
            char *end = NULL;
            errno = 0;
            long number = strtol(argv[++i], &end, 10);
            if (errno != 0 || end == argv[i] || *end != '\0' || number < 0 || number > MAX_SECONDS) {
                usage(argv[0]);
                return 2;
            }
            seconds = (int)number;
        } else if (strcmp(argv[i], "--help") == 0) {
            usage(argv[0]);
            return 0;
        } else {
            usage(argv[0]);
            return 2;
        }
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("PID %ld\n", (long)getpid());
    if (install_signal_handlers() != 0) {
        return 1;
    }
    /* A privileged app worker must never leave an orphan holding the remote. */
    pid_t parent = getppid();
    if (parent == 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) != 0) return 1;
    if (getppid() != parent) return 1;
    if (learned_keys_mode) {
        if (learned_device == NULL || learned_device[0] == '\0') { usage(argv[0]); return 2; }
        for (int i = 0; i < 6; ++i) {
            if (learned_codes[i] == 0) { usage(argv[0]); return 2; }
            for (int j = 0; j < i; ++j)
                if (learned_codes[i] == learned_codes[j]) { usage(argv[0]); return 2; }
        }
        return listen_learned_keys(learned_device, learned_codes, seconds);
    }
    if (learned_gesture_mode) {
        if (learned_device == NULL || learned_device[0] == '\0' || !protocol_set) { usage(argv[0]); return 2; }
        return listen_named_gesture(learned_device, learned_consumer, learned_protocol, seconds);
    }
    int64_t start = monotonic_ms();
    if (start < 0) return 1;
    int64_t deadline = seconds == 0 ? INT64_MAX : start + seconds * 1000LL;
    bool waiting_logged = false;
    while (!stop_signal && monotonic_ms() < deadline) {
        int fds[2] = {-1, -1};
        bool beauty = false;
        int found_profile = -1;
        char path[PATH_MAX] = {0};
        for (int profile = 0; profile < 2; ++profile) {
            if ((profile == 0 && !ble_m3_enabled) || (profile == 1 && !beauty_r1_enabled)) continue;
            int candidate[2] = {-1, -1};
            for (int i = 0; i < 2; ++i)
                candidate[i] = open_target(path, sizeof(path), device_sets[profile][i], true);
            if (candidate[0] >= 0 && candidate[1] >= 0) {
                if (found_profile >= 0) {
                    for (int i = 0; i < 2; ++i) close(candidate[i]);
                    for (int i = 0; i < 2; ++i) close(fds[i]);
                    fprintf(stderr, "ERROR multiple supported remotes connected; disconnect one before mapping\n");
                    if (probe) return 3;
                    fds[0] = fds[1] = -1;
                    found_profile = -2;
                    break;
                }
                found_profile = profile;
                fds[0] = candidate[0];
                fds[1] = candidate[1];
                beauty = profile == 1;
            } else {
                for (int i = 0; i < 2; ++i) if (candidate[i] >= 0) close(candidate[i]);
            }
        }
        if (found_profile < 0) {
            if (probe) return found_profile == -2 ? 3 : 1;
            if (!waiting_logged) {
                printf(found_profile == -2 ? "WAITING_FOR_DEVICE multiple_supported_remotes\n" : "WAITING_FOR_DEVICE\n");
                waiting_logged = true;
            }
            struct timespec delay = {.tv_sec=0, .tv_nsec=200000000};
            nanosleep(&delay, NULL);
            continue;
        }
        int result = 0;
        bool grabbed[2] = {false, false};
        for (int i = 0; i < 2; ++i) {
            if (ioctl(fds[i], EVIOCGRAB, 1) != 0) {
                fprintf(stderr, "GRAB_FAILED %s errno=%d message=%s\n", device_sets[found_profile][i], errno, strerror(errno));
                result = 3; break;
            }
            grabbed[i] = true;
            printf("GRAB_OK %s\n", device_sets[found_profile][i]);
        }
        printf("REMOTE_PROFILE %s\n", beauty ? "Beauty-R1" : "BLE-M3");
        waiting_logged = false;
        if (result == 0 && !probe) result = listen_events(fds, deadline, beauty);
        for (int i = 0; i < 2; ++i) {
            if (grabbed[i]) ioctl(fds[i], EVIOCGRAB, 0);
            close(fds[i]);
        }
        printf("RELEASED\n");
        if (result != 4 || probe) return result;
    }
    printf("STOPPED\n");
    return 0;
}
