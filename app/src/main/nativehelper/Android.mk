LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := trackpad_helper
LOCAL_SRC_FILES := trackpad_helper.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror
LOCAL_LDLIBS := -llog
include $(BUILD_EXECUTABLE)
