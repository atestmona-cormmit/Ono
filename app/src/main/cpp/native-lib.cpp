#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include "node.h"

static std::string toStr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wolf_localmanager_NodeBridge_startNode(JNIEnv *env, jobject /*thiz*/, jobjectArray args,
                                                jstring logPath, jstring tmpDir, jstring homeDir) {
    // stdout/stderr -> file (useful for startup errors)
    std::string lp = toStr(env, logPath);
    int fd = open(lp.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) { dup2(fd, 1); dup2(fd, 2); }

    setenv("TMPDIR", toStr(env, tmpDir).c_str(), 1);
    setenv("HOME", toStr(env, homeDir).c_str(), 1);

    jsize argc = env->GetArrayLength(args);
    std::vector<std::string> v;
    size_t total = 0;
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(args, i);
        v.push_back(toStr(env, s));
        env->DeleteLocalRef(s);
        total += v.back().size() + 1;
    }

    // node::Start needs argv in one contiguous block (uv_setup_args)
    char *buf = (char *) calloc(total, 1);
    char **argv = (char **) calloc(argc + 1, sizeof(char *));
    char *cur = buf;
    for (jsize i = 0; i < argc; i++) {
        memcpy(cur, v[i].c_str(), v[i].size() + 1);
        argv[i] = cur;
        cur += v[i].size() + 1;
    }
    argv[argc] = nullptr;
    return node::Start((int) argc, argv);
}
