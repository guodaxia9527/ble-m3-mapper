/* Bounded diagnostic recording of this remote's two input interfaces only. */
#define _POSIX_C_SOURCE 200809L
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t stopped;
static void stop_recording(int value) { stopped = value; }

static int64_t milliseconds(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return -1;
    return (int64_t)value.tv_sec * 1000 + value.tv_nsec / 1000000;
}

static int open_named(const char *wanted) {
    DIR *directory = opendir("/dev/input");
    if (!directory) return -1;
    struct dirent *entry;
    int selected = -1;
    while ((entry = readdir(directory))) {
        if (strncmp(entry->d_name, "event", 5)) continue;
        char path[300], name[256] = {0};
        int length = snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        if (length < 0 || (size_t)length >= sizeof(path)) continue;
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 && !strcmp(name, wanted)) {
            if (selected >= 0) {
                close(selected); close(fd); selected = -1;
                fprintf(stderr, "ERROR duplicate device: %s\n", wanted);
                break;
            }
            selected = fd;
        } else close(fd);
    }
    closedir(directory);
    return selected;
}

int main(int argc, char **argv) {
    int seconds = 180;
    if (argc == 2) {
        char *end;
        long number = strtol(argv[1], &end, 10);
        if (end == argv[1] || *end || number < 1 || number > 600) return 2;
        seconds = (int)number;
    } else if (argc != 1) return 2;
    setvbuf(stdout, NULL, _IONBF, 0);
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = stop_recording;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGHUP, &action, NULL);
    sigaction(SIGPIPE, &action, NULL);
    const char *names[] = {"BLE-M3 Mouse", "BLE-M3 Consumer Control"};
    const char *labels[] = {"mouse", "consumer"};
    struct pollfd devices[2] = {{.fd = -1, .events = POLLIN}, {.fd = -1, .events = POLLIN}};
    bool grabbed[2] = {false, false};
    int result = 1;
    printf("PID %ld\n", (long)getpid());
    int64_t start = milliseconds();
    if (start < 0) goto cleanup;
    result = 0;
    bool connected = false;
    bool waiting_logged = false;
    while (!stopped) {
        int64_t now = milliseconds();
        if (now < 0) { result = 1; break; }
        if (now - start >= seconds * 1000LL) break;
        if (!connected) {
            for (int i = 0; i < 2; ++i) devices[i].fd = open_named(names[i]);
            if (devices[0].fd < 0 || devices[1].fd < 0) {
                for (int i = 0; i < 2; ++i) {
                    if (devices[i].fd >= 0) close(devices[i].fd);
                    devices[i].fd = -1;
                }
                if (!waiting_logged) { printf("WAITING_FOR_RECONNECT\n"); waiting_logged = true; }
                struct timespec delay = {.tv_sec = 0, .tv_nsec = 200000000};
                nanosleep(&delay, NULL);
                continue;
            }
            for (int i = 0; i < 2; ++i) {
                if (ioctl(devices[i].fd, EVIOCGRAB, 1) != 0) {
                    fprintf(stderr, "GRAB_FAILED %s errno=%d %s\n", names[i], errno, strerror(errno));
                    result = 1;
                    goto cleanup;
                }
                grabbed[i] = true;
                printf("GRAB_OK %s\n", names[i]);
            }
            connected = true;
            waiting_logged = false;
            printf("RECORD_READY remaining_ms=%lld; no actions injected\n",
                   (long long)(seconds * 1000LL - (now - start)));
        }
        int ready = poll(devices, 2, 200);
        if (ready < 0) { if (errno == EINTR) continue; result = 1; break; }
        for (int i = 0; i < 2; ++i) {
            if (devices[i].revents & (POLLERR | POLLHUP | POLLNVAL)) {
                printf("DISCONNECTED source=%s flags=%d\n", labels[i], devices[i].revents);
                connected = false;
                break;
            }
            if (!(devices[i].revents & POLLIN)) continue;
            struct input_event event;
            ssize_t count = read(devices[i].fd, &event, sizeof(event));
            if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            if (count != sizeof(event)) {
                printf("READ_INTERRUPTED source=%s count=%lld errno=%d\n", labels[i], (long long)count, errno);
                connected = false;
                break;
            }
            if (event.type != EV_SYN) {
                printf("EVENT source=%s t=%lld.%06lld type=%u code=%u value=%d\n",
                       labels[i], (long long)event.time.tv_sec, (long long)event.time.tv_usec,
                       event.type, event.code, event.value);
            }
        }
        if (!connected) {
            for (int i = 0; i < 2; ++i) {
                if (grabbed[i]) ioctl(devices[i].fd, EVIOCGRAB, 0);
                if (devices[i].fd >= 0) close(devices[i].fd);
                devices[i].fd = -1;
                grabbed[i] = false;
            }
        }
    }
cleanup:
    for (int i = 0; i < 2; ++i) {
        if (devices[i].fd >= 0) {
            if (grabbed[i]) ioctl(devices[i].fd, EVIOCGRAB, 0);
            close(devices[i].fd);
        }
    }
    printf("RECORD_STOPPED released=all\n");
    return result;
}
