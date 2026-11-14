/* fdk-aac includes AOSP's log/log.h on Android; the NDK has none. Only this call is used. */
#pragma once
#define android_errorWriteLog(tag, info) ((void)0)
