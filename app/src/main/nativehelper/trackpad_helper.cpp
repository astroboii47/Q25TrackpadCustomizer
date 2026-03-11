#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>

namespace {

constexpr const char* kLogTag = "Q25TrackpadHelper";
constexpr int kTouchIdleTimeoutMs = 42;
constexpr int kPollTimeoutMs = 10;
constexpr int kStartupIgnoreMotionMs = 160;
constexpr int kControlPollIntervalMs = 50;
constexpr int kTouchActivationThresholdPx = 96;
constexpr int kMinInitialDragPx = 26;
constexpr float kEdgeMarginRatio = 0.18f;
constexpr int kMinEdgeMarginPx = 96;
constexpr float kTouchAnchorYRatio = 0.5f;

std::atomic<bool> g_running(true);
int g_input_fd = -1;
int g_keyboard_fd = -1;
int g_touch_fd = -1;
int g_mouse_fd = -1;

struct TouchConfig {
    int width = 720;
    int height = 720;
    float scale = 6.0f;
    bool horizontal_enabled = true;
    bool invert_vertical = false;
    bool invert_horizontal = false;
};

struct HelperOptions {
    bool grab_device = false;
    bool emit_rel = false;
    bool touch_scroll_enabled = false;
    bool mode_switch_enabled = false;
};

struct TouchState {
    bool active = false;
    int x = 0;
    int y = 0;
    int tracking_id = 1;
    int last_motion_ms = 0;
    int pending_activation_dx = 0;
    int pending_activation_dy = 0;
    int last_vertical_sign = 0;
};

void HandleSignal(int) {
    g_running.store(false);
}

int NowMonotonicMs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int>(ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL);
}

bool EmitEvent(int fd, __u16 type, __u16 code, __s32 value) {
    if (fd < 0) return false;
    input_event ev{};
    ev.type = type;
    ev.code = code;
    ev.value = value;
    return write(fd, &ev, sizeof(ev)) == static_cast<ssize_t>(sizeof(ev));
}

bool EmitKeyboardEvent(__u16 type, __u16 code, __s32 value) {
    return EmitEvent(g_keyboard_fd, type, code, value);
}

bool EmitTouchEvent(__u16 type, __u16 code, __s32 value) {
    return EmitEvent(g_touch_fd, type, code, value);
}

bool EmitMouseEvent(__u16 type, __u16 code, __s32 value) {
    return EmitEvent(g_mouse_fd, type, code, value);
}

void ReleaseGrab() {
    if (g_input_fd >= 0) {
        ioctl(g_input_fd, EVIOCGRAB, 0);
        close(g_input_fd);
        g_input_fd = -1;
    }
}

bool ConfigureAbs(int fd, __u16 code, int min, int max) {
    uinput_abs_setup setup{};
    setup.code = code;
    setup.absinfo.minimum = min;
    setup.absinfo.maximum = max;
    setup.absinfo.value = 0;
    setup.absinfo.fuzz = 0;
    setup.absinfo.flat = 0;
    setup.absinfo.resolution = 0;
    return ioctl(fd, UI_ABS_SETUP, &setup) == 0;
}

bool CreateUinputKeyboard() {
    g_keyboard_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_keyboard_fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "open keyboard uinput failed: %s", strerror(errno));
        return false;
    }

    ioctl(g_keyboard_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(g_keyboard_fd, UI_SET_EVBIT, EV_MSC);
    ioctl(g_keyboard_fd, UI_SET_EVBIT, EV_REP);
    ioctl(g_keyboard_fd, UI_SET_EVBIT, EV_SYN);
    ioctl(g_keyboard_fd, UI_SET_MSCBIT, MSC_SCAN);
    for (int code = 0; code <= KEY_MAX; ++code) {
        ioctl(g_keyboard_fd, UI_SET_KEYBIT, code);
    }
    for (int code = BTN_MISC; code <= BTN_TASK; ++code) {
        ioctl(g_keyboard_fd, UI_SET_KEYBIT, code);
    }

    uinput_setup setup{};
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "Q25_keyboard");
    setup.id.bustype = BUS_HOST;
    setup.id.vendor = 0x0001;
    setup.id.product = 0x0001;
    setup.id.version = 1;

    if (ioctl(g_keyboard_fd, UI_DEV_SETUP, &setup) != 0 || ioctl(g_keyboard_fd, UI_DEV_CREATE) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "keyboard uinput create failed: %s", strerror(errno));
        close(g_keyboard_fd);
        g_keyboard_fd = -1;
        return false;
    }

    usleep(100000);
    return true;
}

bool CreateUinputTouchscreen(const TouchConfig& config) {
    g_touch_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_touch_fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "open touch uinput failed: %s", strerror(errno));
        return false;
    }

    ioctl(g_touch_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(g_touch_fd, UI_SET_EVBIT, EV_ABS);
    ioctl(g_touch_fd, UI_SET_EVBIT, EV_SYN);
    ioctl(g_touch_fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(g_touch_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    ioctl(g_touch_fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(g_touch_fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(g_touch_fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(g_touch_fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(g_touch_fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);

    if (!ConfigureAbs(g_touch_fd, ABS_MT_SLOT, 0, 0) ||
        !ConfigureAbs(g_touch_fd, ABS_MT_TRACKING_ID, -1, 65535) ||
        !ConfigureAbs(g_touch_fd, ABS_MT_POSITION_X, 0, config.width - 1) ||
        !ConfigureAbs(g_touch_fd, ABS_MT_POSITION_Y, 0, config.height - 1) ||
        !ConfigureAbs(g_touch_fd, ABS_MT_TOUCH_MAJOR, 0, 8)) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "touch abs setup failed: %s", strerror(errno));
        close(g_touch_fd);
        g_touch_fd = -1;
        return false;
    }

    uinput_setup setup{};
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "Q25 Helper Touch");
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x18d1;
    setup.id.product = 0x4ee8;
    setup.id.version = 1;

    if (ioctl(g_touch_fd, UI_DEV_SETUP, &setup) != 0 || ioctl(g_touch_fd, UI_DEV_CREATE) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "touch uinput create failed: %s", strerror(errno));
        close(g_touch_fd);
        g_touch_fd = -1;
        return false;
    }

    usleep(120000);
    return true;
}

bool CreateUinputMouse() {
    g_mouse_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_mouse_fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "open mouse uinput failed: %s", strerror(errno));
        return false;
    }

    ioctl(g_mouse_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(g_mouse_fd, UI_SET_EVBIT, EV_REL);
    ioctl(g_mouse_fd, UI_SET_EVBIT, EV_SYN);
    ioctl(g_mouse_fd, UI_SET_KEYBIT, BTN_MOUSE);
    ioctl(g_mouse_fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(g_mouse_fd, UI_SET_RELBIT, REL_X);
    ioctl(g_mouse_fd, UI_SET_RELBIT, REL_Y);

    uinput_setup setup{};
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "Q25 Helper Mouse");
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x18d1;
    setup.id.product = 0x4ee9;
    setup.id.version = 1;

    if (ioctl(g_mouse_fd, UI_DEV_SETUP, &setup) != 0 || ioctl(g_mouse_fd, UI_DEV_CREATE) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "mouse uinput create failed: %s", strerror(errno));
        close(g_mouse_fd);
        g_mouse_fd = -1;
        return false;
    }

    usleep(100000);
    return true;
}

void DestroyUinputDevice(int* fd) {
    if (*fd >= 0) {
        ioctl(*fd, UI_DEV_DESTROY);
        close(*fd);
        *fd = -1;
    }
}

int Clamp(int value, int min, int max) {
    if (value < min) return min;
    if (value > max) return max;
    return value;
}

int ComputeAnchorY(const TouchConfig& config) {
    const int anchor_y = static_cast<int>(config.height * kTouchAnchorYRatio);
    return Clamp(anchor_y, kMinEdgeMarginPx, config.height - kMinEdgeMarginPx);
}

void EmitTouchFrame(int x, int y, int tracking_id, bool down) {
    EmitTouchEvent(EV_ABS, ABS_MT_SLOT, 0);
    if (down) {
        EmitTouchEvent(EV_ABS, ABS_MT_TRACKING_ID, tracking_id);
        EmitTouchEvent(EV_ABS, ABS_MT_POSITION_X, x);
        EmitTouchEvent(EV_ABS, ABS_MT_POSITION_Y, y);
        EmitTouchEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 4);
        EmitTouchEvent(EV_KEY, BTN_TOUCH, 1);
    } else {
        EmitTouchEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
        EmitTouchEvent(EV_KEY, BTN_TOUCH, 0);
    }
    EmitTouchEvent(EV_SYN, SYN_REPORT, 0);
}

void TouchDown(TouchState* state, int x, int y) {
    state->active = true;
    state->x = x;
    state->y = y;
    state->pending_activation_dx = 0;
    state->pending_activation_dy = 0;
    EmitTouchFrame(x, y, state->tracking_id++, true);
}

void TouchMove(TouchState* state, int x, int y) {
    state->x = x;
    state->y = y;
    EmitTouchEvent(EV_ABS, ABS_MT_SLOT, 0);
    EmitTouchEvent(EV_ABS, ABS_MT_POSITION_X, x);
    EmitTouchEvent(EV_ABS, ABS_MT_POSITION_Y, y);
    EmitTouchEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 4);
    EmitTouchEvent(EV_SYN, SYN_REPORT, 0);
}

void TouchUp(TouchState* state) {
    if (!state->active) return;
    EmitTouchFrame(state->x, state->y, 0, false);
    state->active = false;
    state->pending_activation_dx = 0;
    state->pending_activation_dy = 0;
    state->last_vertical_sign = 0;
}

void RecenterTouch(TouchState* state, const TouchConfig& config) {
    TouchUp(state);
    const int center_x = config.width / 2;
    const int center_y = ComputeAnchorY(config);
    TouchDown(state, center_x, center_y);
}

void HandleMotion(const TouchConfig& config, TouchState* state, int dx, int dy) {
    if (dx == 0 && dy == 0) return;

    const int center_x = config.width / 2;
    const int center_y = ComputeAnchorY(config);
    const int margin_x = std::max(kMinEdgeMarginPx, static_cast<int>(config.width * kEdgeMarginRatio));
    const int margin_y = std::max(kMinEdgeMarginPx, static_cast<int>(config.height * kEdgeMarginRatio));
    bool just_activated = false;

    if (!state->active) {
        state->pending_activation_dx += dx;
        state->pending_activation_dy += dy;

        float pending_move_x = static_cast<float>(state->pending_activation_dx) * config.scale;
        float pending_move_y = static_cast<float>(state->pending_activation_dy) * config.scale;

        if (!config.horizontal_enabled) {
            pending_move_x = 0.0f;
        }

        if (std::max(std::abs(pending_move_x), std::abs(pending_move_y)) < kTouchActivationThresholdPx) {
            return;
        }

        dx = state->pending_activation_dx;
        dy = state->pending_activation_dy;
        TouchDown(state, center_x, center_y);
        just_activated = true;
    }

    float move_x = static_cast<float>(dx) * config.scale;
    float move_y = static_cast<float>(dy) * config.scale;

    if (!config.horizontal_enabled) {
        move_x = 0.0f;
    }
    if (config.invert_horizontal) {
        move_x = -move_x;
    }
    if (config.invert_vertical) {
        move_y = -move_y;
    }

    if (just_activated) {
        if (std::abs(move_y) >= std::abs(move_x) && move_y != 0.0f && std::abs(move_y) < kMinInitialDragPx) {
            move_y = move_y > 0.0f ? static_cast<float>(kMinInitialDragPx) : static_cast<float>(-kMinInitialDragPx);
        } else if (move_x != 0.0f && std::abs(move_x) < kMinInitialDragPx) {
            move_x = move_x > 0.0f ? static_cast<float>(kMinInitialDragPx) : static_cast<float>(-kMinInitialDragPx);
        }
    }

    int vertical_sign = 0;
    if (move_y > 0.0f) {
        vertical_sign = 1;
    } else if (move_y < 0.0f) {
        vertical_sign = -1;
    }

    if (state->active && vertical_sign != 0 && state->last_vertical_sign != 0 && vertical_sign != state->last_vertical_sign) {
        RecenterTouch(state, config);
    }
    if (vertical_sign != 0) {
        state->last_vertical_sign = vertical_sign;
    }

    const int next_x = Clamp(state->x + static_cast<int>(move_x), margin_x, config.width - margin_x);
    const int next_y = Clamp(state->y + static_cast<int>(move_y), margin_y, config.height - margin_y);

    if (next_x == state->x && next_y == state->y) {
        state->last_motion_ms = NowMonotonicMs();
        return;
    }

    TouchMove(state, next_x, next_y);
    state->last_motion_ms = NowMonotonicMs();

    const bool near_edge =
        next_x <= margin_x || next_x >= (config.width - margin_x) ||
        next_y <= margin_y || next_y >= (config.height - margin_y);
    if (near_edge) {
        RecenterTouch(state, config);
        state->last_motion_ms = NowMonotonicMs();
        return;
    }
}

bool ParseTouchConfig(int argc, char** argv, TouchConfig* config, HelperOptions* options) {
    options->grab_device = false;
    options->emit_rel = false;
    options->touch_scroll_enabled = false;

    for (int i = 2; i < argc; ++i) {
        if (strcmp(argv[i], "--grab") == 0) {
            options->grab_device = true;
            continue;
        }
        if (strcmp(argv[i], "--emit-rel") == 0) {
            options->emit_rel = true;
            continue;
        }
        if (strcmp(argv[i], "--mode-switch") == 0) {
            options->mode_switch_enabled = true;
            continue;
        }
        if (strcmp(argv[i], "--control") == 0) {
            if (i + 1 >= argc) return false;
            ++i;
            continue;
        }
        if (strcmp(argv[i], "--touch-scroll") == 0) {
            if (i + 6 >= argc) return false;
            options->touch_scroll_enabled = true;
            config->width = atoi(argv[i + 1]);
            config->height = atoi(argv[i + 2]);
            config->scale = static_cast<float>(atof(argv[i + 3]));
            config->horizontal_enabled = atoi(argv[i + 4]) != 0;
            config->invert_vertical = atoi(argv[i + 5]) != 0;
            config->invert_horizontal = atoi(argv[i + 6]) != 0;
            i += 6;
            continue;
        }
        return false;
    }

    if (config->width <= 0 || config->height <= 0 || config->scale <= 0.0f) {
        return false;
    }

    return true;
}

bool ReadControlActive(const char* control_path) {
    if (control_path == nullptr) return false;
    FILE* file = fopen(control_path, "r");
    if (file == nullptr) return false;
    const int ch = fgetc(file);
    fclose(file);
    return ch == '1';
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s /dev/input/eventX [--grab] [--touch-scroll width height scale horiz invV invH]\n", argv[0]);
        return 1;
    }

    signal(SIGINT, HandleSignal);
    signal(SIGTERM, HandleSignal);
    signal(SIGHUP, HandleSignal);

    TouchConfig config;
    HelperOptions options;
    if (!ParseTouchConfig(argc, argv, &config, &options)) {
        fprintf(stderr, "invalid arguments\n");
        return 1;
    }

    const char* control_path = nullptr;
    for (int i = 2; i < argc; ++i) {
        if (strcmp(argv[i], "--control") == 0 && i + 1 < argc) {
            control_path = argv[i + 1];
            ++i;
        }
    }

    const char* event_path = argv[1];
    g_input_fd = open(event_path, O_RDONLY | O_NONBLOCK);
    if (g_input_fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "open failed: %s", strerror(errno));
        return 2;
    }

    const bool persistent_control = control_path != nullptr;
    bool helper_active = false;
    int next_control_poll_ms = 0;

    const bool need_keyboard = options.grab_device || persistent_control;
    const bool need_touch = persistent_control || options.touch_scroll_enabled;
    const bool need_mouse = options.grab_device && options.emit_rel && !options.touch_scroll_enabled;

    if (need_keyboard || need_touch || need_mouse) {
        if ((need_keyboard && !CreateUinputKeyboard()) ||
            (need_touch && !CreateUinputTouchscreen(config)) ||
            (need_mouse && !CreateUinputMouse())) {
            ReleaseGrab();
            DestroyUinputDevice(&g_keyboard_fd);
            DestroyUinputDevice(&g_touch_fd);
            DestroyUinputDevice(&g_mouse_fd);
            return 4;
        }
        if (options.grab_device && !persistent_control) {
            if (ioctl(g_input_fd, EVIOCGRAB, 1) != 0) {
                __android_log_print(ANDROID_LOG_ERROR, kLogTag, "EVIOCGRAB failed: %s", strerror(errno));
                ReleaseGrab();
                DestroyUinputDevice(&g_keyboard_fd);
                DestroyUinputDevice(&g_touch_fd);
                DestroyUinputDevice(&g_mouse_fd);
                return 3;
            }
            helper_active = true;
        }
    }

    pollfd pfd{};
    pfd.fd = g_input_fd;
    pfd.events = POLLIN;

    TouchState touch_state;
    int pending_dx = 0;
    int pending_dy = 0;
    int pending_scan_code = -1;
    bool pending_btn_mouse = false;
    bool pending_btn_right = false;
    int pending_btn_mouse_value = 0;
    int pending_btn_right_value = 0;
    const int startup_ignore_until_ms = NowMonotonicMs() + kStartupIgnoreMotionMs;

    while (g_running.load()) {
        const int now_ms = NowMonotonicMs();
        if (persistent_control && now_ms >= next_control_poll_ms) {
            next_control_poll_ms = now_ms + kControlPollIntervalMs;
            const bool desired_active = ReadControlActive(control_path);
            if (desired_active != helper_active) {
                if (desired_active) {
                    if (ioctl(g_input_fd, EVIOCGRAB, 1) == 0) {
                        helper_active = true;
                    }
                } else {
                    TouchUp(&touch_state);
                    ioctl(g_input_fd, EVIOCGRAB, 0);
                    helper_active = false;
                    pending_dx = 0;
                    pending_dy = 0;
                    pending_scan_code = -1;
                    pending_btn_mouse = false;
                    pending_btn_right = false;
                }
            }
        }

        const int poll_result = poll(&pfd, 1, kPollTimeoutMs);
        if (poll_result > 0 && (pfd.revents & POLLIN)) {
            input_event ev{};
            while (read(g_input_fd, &ev, sizeof(ev)) == static_cast<ssize_t>(sizeof(ev))) {
                if (ev.type == EV_REL) {
                    if (ev.code == REL_X) pending_dx += ev.value;
                    if (ev.code == REL_Y) pending_dy += ev.value;
                } else if (ev.type == EV_MSC && ev.code == MSC_SCAN) {
                    pending_scan_code = ev.value;
                } else if (ev.type == EV_KEY) {
                    if (options.emit_rel) {
                        if (ev.code == BTN_MOUSE) {
                            pending_btn_mouse = true;
                            pending_btn_mouse_value = ev.value;
                        } else if (ev.code == BTN_RIGHT) {
                            pending_btn_right = true;
                            pending_btn_right_value = ev.value;
                        }
                    }
                    if (helper_active) {
                        const bool is_mouse_button = ev.code == BTN_MOUSE || ev.code == BTN_RIGHT;
                        const bool is_mode_switch_press =
                            (options.mode_switch_enabled && ev.code == BTN_MOUSE && pending_scan_code == 5);
                        const bool is_scroll_mode_switch_press =
                            (options.mode_switch_enabled &&
                             options.touch_scroll_enabled &&
                             ev.code == KEY_ENTER &&
                             (pending_scan_code == 5 || pending_scan_code == 0x00090001 || pending_scan_code < 0));

                        if ((options.grab_device && options.emit_rel && !options.touch_scroll_enabled && is_mode_switch_press) ||
                            is_scroll_mode_switch_press) {
                            if (ev.value == 1) {
                                printf("SWITCH\n");
                                fflush(stdout);
                            }
                        } else if (options.grab_device && options.emit_rel && !options.touch_scroll_enabled && is_mouse_button) {
                            EmitMouseEvent(EV_KEY, ev.code, ev.value);
                            EmitMouseEvent(EV_SYN, SYN_REPORT, 0);
                        } else {
                            if (pending_scan_code >= 0) {
                                EmitKeyboardEvent(EV_MSC, MSC_SCAN, pending_scan_code);
                            }
                            EmitKeyboardEvent(EV_KEY, ev.code, ev.value);
                            EmitKeyboardEvent(EV_SYN, SYN_REPORT, 0);
                        }
                    }
                    pending_scan_code = -1;
                } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                    if (options.emit_rel) {
                        if (pending_dx != 0 || pending_dy != 0) {
                            printf("REL %d %d\n", pending_dx, pending_dy);
                            fflush(stdout);
                        }
                        if (pending_btn_mouse) {
                            printf("KEY %d %d\n", BTN_MOUSE, pending_btn_mouse_value);
                            fflush(stdout);
                        }
                        if (pending_btn_right) {
                            printf("KEY %d %d\n", BTN_RIGHT, pending_btn_right_value);
                            fflush(stdout);
                        }
                    }
                    if (helper_active && options.grab_device && options.emit_rel && !options.touch_scroll_enabled && (pending_dx != 0 || pending_dy != 0)) {
                        EmitMouseEvent(EV_REL, REL_X, pending_dx);
                        EmitMouseEvent(EV_REL, REL_Y, pending_dy);
                        EmitMouseEvent(EV_SYN, SYN_REPORT, 0);
                    }
                    if (helper_active && options.touch_scroll_enabled && NowMonotonicMs() >= startup_ignore_until_ms) {
                        HandleMotion(config, &touch_state, pending_dx, pending_dy);
                    }
                    pending_dx = 0;
                    pending_dy = 0;
                    pending_scan_code = -1;
                    pending_btn_mouse = false;
                    pending_btn_right = false;
                }
            }

            if (errno != EAGAIN && errno != EINTR) {
                break;
            }
        } else if (poll_result < 0 && errno != EINTR) {
            break;
        }

        if (touch_state.active && now_ms - touch_state.last_motion_ms >= kTouchIdleTimeoutMs) {
            TouchUp(&touch_state);
        }
    }

    TouchUp(&touch_state);
    ReleaseGrab();
    DestroyUinputDevice(&g_keyboard_fd);
    DestroyUinputDevice(&g_touch_fd);
    DestroyUinputDevice(&g_mouse_fd);
    return 0;
}
