#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

using HookFunction = int (*)(void *, void *, void **);
using LibraryLoaded = void (*)(const char *, void *);
struct NativeAPIEntries {
    uint32_t version;
    HookFunction hook;
    int (*unhook)(void *);
};

static HookFunction hookFunction;
static int (*originalProtect)(void *, size_t, int);
static bool (*originalRegionCheck)(void *, int *, int *);
static bool (*originalEnvironmentCheck)(int);
static void *failureAddress;
static uintptr_t jiaguBase;
static std::atomic_flag scanning = ATOMIC_FLAG_INIT;

static bool diagnosticEnvironmentCheck(int mode) {
    void *caller = __builtin_return_address(0);
    uintptr_t stack;
    asm volatile("mov %0, sp" : "=r"(stack));
    bool result = originalEnvironmentCheck(mode);
    uintptr_t callerOffset = reinterpret_cast<uintptr_t>(caller) - jiaguBase;
    if (result && ((mode == 0 && callerOffset == 0x189840) || (mode == 1 && callerOffset == 0x232a38))) {
        // shortcut: frame and LSPosed header fingerprint are specific to the confirmed APK and module
        // versions
        const auto *frame = reinterpret_cast<const unsigned char *>(stack - 0x650);
        uintptr_t start, end;
        int flagged, count;
        memcpy(&start, frame + 0xa8, sizeof(start));
        memcpy(&end, frame + 0xa0, sizeof(end));
        memcpy(&flagged, frame + 0x2c, sizeof(flagged));
        memcpy(&count, frame + 0x28, sizeof(count));
        if (mode == 1 && flagged == 0 && count >= 15) {
            const auto *words = reinterpret_cast<const uintptr_t *>(frame + 0x88);
            size_t length = (frame[0x88] & 1) ? words[1] : frame[0x88] >> 1;
            const void *text = (frame[0x88] & 1) ? reinterpret_cast<const void *>(words[2])
                                                 : reinterpret_cast<const void *>(frame + 0x89);
            char line[1024];
            size_t copied = length < sizeof(line) ? length : sizeof(line) - 1;
            memcpy(line, text, copied);
            line[copied] = 0;
            if (strstr(line, "/apex/com.android.art/lib64/libart.so") != nullptr) {
                __android_log_print(ANDROID_LOG_INFO, "LvTu",
                                    "accepted periodic libart split count=%d", count);
                return false;
            }
        }
        if (mode == 1)
            __android_log_print(ANDROID_LOG_INFO, "LvTuNative",
                                "periodic environment count=%d flagged=%d start=%p end=%p", count, flagged,
                                reinterpret_cast<void *>(start), reinterpret_cast<void *>(end));
        if (flagged == 1 && frame[0xd8] == 0 && frame[0xba] == 'x' && end > start && end - start >= 568) {
            uint64_t fingerprint = 14695981039346656037ULL;
            auto *header = reinterpret_cast<const unsigned char *>(start);
            for (size_t i = 0; i < 568; ++i)
                fingerprint = (fingerprint ^ header[i]) * 1099511628211ULL;
            if (fingerprint == 0x99f23a0b77d6a4c1ULL) {
                __android_log_print(ANDROID_LOG_INFO, "LvTu",
                                    "accepted confirmed LSPosed anonymous ELF at %p for initialization",
                                    reinterpret_cast<void *>(start));
                return false;
            }
        }
    }
    __android_log_print(ANDROID_LOG_INFO, "LvTuNative", "environment mode=%d result=%d caller=%p", mode, result,
                        caller);
    return result;
}

static bool diagnosticRegionCheck(void *line, int *apexCount, int *systemCount) {
    int apexBefore = *apexCount;
    int systemBefore = *systemCount;
    bool result = originalRegionCheck(line, apexCount, systemCount);
    auto *bytes = static_cast<unsigned char *>(line);
    auto *words = static_cast<uintptr_t *>(line);
    const char *text =
        (bytes[0] & 1) ? reinterpret_cast<const char *>(words[2]) : reinterpret_cast<const char *>(bytes + 1);
    size_t length = (bytes[0] & 1) ? words[1] : bytes[0] >> 1;
    __android_log_print(ANDROID_LOG_INFO, "LvTuNative", "region result=%d apex=%d->%d system=%d->%d line=%.*s",
                        result, apexBefore, *apexCount, systemBefore, *systemCount,
                        static_cast<int>(length > 2048 ? 2048 : length), text);
    if (result && apexBefore >= 4 && *apexCount == apexBefore + 1 &&
        strstr(text, "/apex/com.android.art/lib64/libart.so") != nullptr) {
        __android_log_print(ANDROID_LOG_INFO, "LvTuNative", "libart split mapping accepted");
        return false;
    }
    return result;
}

static bool failureSignature(const uint32_t *code) {
    static constexpr uint32_t pattern[] = {0xa9bf7bf3, 0,          0xf9400008, 0xaa0003f3, 0xf9401508,
                                           0xd63f0100, 0x52800120, 0,          0xaa1f03e8, 0x6f00e400,
                                           0x8b080269, 0x91008108, 0xf140051f, 0xad000120, 0x54ffff81,
                                           0xa8c17bf3, 0xd65f03c0};
    for (size_t i = 0; i < sizeof(pattern) / sizeof(pattern[0]); ++i) {
        if (i == 1 || i == 7) {
            if ((code[i] & 0xfc000000) != 0x94000000)
                return false;
        } else if (code[i] != pattern[i])
            return false;
    }
    return true;
}

static bool checkSignature(const uint32_t *code, uintptr_t address) {
    static constexpr uint32_t pattern[] = {0xa9ba6ffc, 0xa90167fa, 0xa9025ff8, 0xa90357f6, 0xa9044ff4,
                                           0xa9057bfd, 0xd11b83ff, 0xd53bd054, 0xf9401688, 0xf9036fe8};
    for (size_t i = 0; i < sizeof(pattern) / sizeof(pattern[0]); ++i)
        if (code[i] != pattern[i])
            return false;
    uint32_t branch = code[0x9fc / 4];
    if ((branch & 0xfc000000) != 0x94000000)
        return false;
    int32_t offset = static_cast<int32_t>(branch << 6) >> 4;
    return address + 0x9fc + offset == reinterpret_cast<uintptr_t>(failureAddress);
}

static int diagnosticProtect(void *address, size_t length, int protection) {
    __android_log_print(ANDROID_LOG_DEBUG, "LvTu", "private protect address=%p length=%zu mode=%d",
                        address, length, protection);
    int result = originalProtect(address, length, protection);
    if (result != 0 || (protection & (PROT_READ | PROT_EXEC)) != (PROT_READ | PROT_EXEC) || length < 68 ||
        scanning.test_and_set())
        return result;
    uintptr_t start = (reinterpret_cast<uintptr_t>(address) + 3) & ~uintptr_t(3);
    uintptr_t end = reinterpret_cast<uintptr_t>(address) + length;
    if (failureAddress == nullptr) {
        for (uintptr_t p = start; p + 68 <= end; p += 4) {
            if (failureSignature(reinterpret_cast<const uint32_t *>(p))) {
                failureAddress = reinterpret_cast<void *>(p);
                break;
            }
        }
    }
    if (failureAddress != nullptr && originalRegionCheck == nullptr) {
        for (uintptr_t p = start; p + 0xa00 <= end; p += 4) {
            if (!checkSignature(reinterpret_cast<const uint32_t *>(p), p))
                continue;
            if (p - 0x232b9c != jiaguBase)
                break;
            auto *region = reinterpret_cast<uint32_t *>(p + 0xf0c);
            auto *environment = reinterpret_cast<uint32_t *>(jiaguBase + 0x2316ac);
            if (region[0] != 0xd101c3ff || region[1] != 0xf90013fa || environment[0] != 0xa9ba6ffc ||
                environment[6] != 0xd117c3ff)
                break;
            int regionStatus = hookFunction(region, reinterpret_cast<void *>(diagnosticRegionCheck),
                                            reinterpret_cast<void **>(&originalRegionCheck));
            int environmentStatus =
                hookFunction(environment, reinterpret_cast<void *>(diagnosticEnvironmentCheck),
                             reinterpret_cast<void **>(&originalEnvironmentCheck));
            __android_log_print(ANDROID_LOG_INFO, "LvTu",
                                "compatibility hooks region=%d environment=%d", regionStatus,
                                environmentStatus);
            break;
        }
    }
    scanning.clear();
    return result;
}

static void nativeLibraryLoaded(const char *name, void *handle) {
    if (name == nullptr || strstr(name, "libjiagu_64.so") == nullptr || jiaguBase != 0)
        return;
    void *entry = dlsym(handle, "JNI_OnLoad");
    if (entry == nullptr)
        return;
    jiaguBase = reinterpret_cast<uintptr_t>(entry) - 0x369b54;
    originalProtect = reinterpret_cast<decltype(originalProtect)>(dlsym(RTLD_DEFAULT, "mprotect"));
    const uintptr_t offsets[] = {0x387fc0, 0x6fe18};
    for (uintptr_t offset : offsets) {
        auto **slot = reinterpret_cast<void **>(jiaguBase + offset);
        if (*slot != reinterpret_cast<void *>(originalProtect))
            continue;
        int permissions = 0;
        FILE *maps = fopen("/proc/self/maps", "r");
        if (maps == nullptr)
            return;
        char line[1024], flags[5];
        uintptr_t start, end;
        while (fgets(line, sizeof(line), maps) != nullptr) {
            if (sscanf(line, "%lx-%lx %4s", &start, &end, flags) != 3 ||
                reinterpret_cast<uintptr_t>(slot) < start || reinterpret_cast<uintptr_t>(slot) >= end)
                continue;
            permissions = (flags[0] == 'r' ? PROT_READ : 0) | (flags[1] == 'w' ? PROT_WRITE : 0) |
                          (flags[2] == 'x' ? PROT_EXEC : 0);
            break;
        }
        fclose(maps);
        if (permissions == 0)
            return;
        size_t pageSize = static_cast<size_t>(sysconf(_SC_PAGESIZE));
        uintptr_t page = reinterpret_cast<uintptr_t>(slot) & ~(pageSize - 1);
        if (syscall(__NR_mprotect, page, pageSize, permissions | PROT_WRITE) != 0)
            return;
        *slot = reinterpret_cast<void *>(diagnosticProtect);
        int status = static_cast<int>(syscall(__NR_mprotect, page, pageSize, permissions));
        __android_log_print(ANDROID_LOG_INFO, "LvTu", "private unpack listener installed restore=%d",
                            status);
    }
}

extern "C" __attribute__((visibility("default"), used)) LibraryLoaded
native_init(const NativeAPIEntries *api) {
    hookFunction = api->hook;
    __android_log_print(ANDROID_LOG_INFO, "LvTu", "native API %u private loader callback ready",
                        api->version);
    return nativeLibraryLoaded;
}
