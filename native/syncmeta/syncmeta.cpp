#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {

constexpr char kMagic[8] = {'R', 'S', 'M', 'E', 'T', 'A', '1', '\0'};
constexpr uint32_t kVersion = 1;
constexpr uint32_t kMaxPathBytes = 1024 * 1024;

struct Entry {
    std::string path;
    int64_t seconds;
    int32_t nanoseconds;
};

bool write_exact(FILE* file, const void* data, size_t size) {
    return std::fwrite(data, 1, size, file) == size;
}

bool read_exact(FILE* file, void* data, size_t size) {
    return std::fread(data, 1, size, file) == size;
}

bool valid_relative_path(const std::string& path) {
    if (path.empty() || path == ".") return true;
    if (path.front() == '/' || path.find('\0') != std::string::npos) return false;
    size_t start = 0;
    while (start <= path.size()) {
        const size_t end = path.find('/', start);
        const std::string part = path.substr(start, end == std::string::npos ? std::string::npos : end - start);
        if (part.empty() || part == "." || part == "..") return false;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return true;
}

std::string join_path(const std::string& root, const std::string& relative) {
    if (relative.empty() || relative == ".") return root;
    return root + "/" + relative;
}

int path_depth(const std::string& path) {
    if (path.empty() || path == ".") return 0;
    return 1 + static_cast<int>(std::count(path.begin(), path.end(), '/'));
}

bool collect_directories(
    const std::string& root,
    const std::string& relative,
    std::vector<Entry>* entries
) {
    const std::string full = join_path(root, relative);
    struct stat info {};
    if (lstat(full.c_str(), &info) != 0) {
        std::fprintf(stderr, "lstat failed: %s: %s\n", relative.c_str(), std::strerror(errno));
        return false;
    }
    if (!S_ISDIR(info.st_mode)) return true;

    entries->push_back(Entry{
        relative.empty() ? "." : relative,
        static_cast<int64_t>(info.st_mtim.tv_sec),
        static_cast<int32_t>(info.st_mtim.tv_nsec)
    });

    DIR* directory = opendir(full.c_str());
    if (directory == nullptr) {
        std::fprintf(stderr, "opendir failed: %s: %s\n", relative.c_str(), std::strerror(errno));
        return false;
    }

    bool success = true;
    errno = 0;
    while (dirent* item = readdir(directory)) {
        const std::string name(item->d_name);
        if (name == "." || name == "..") continue;
        const std::string child = relative.empty() ? name : relative + "/" + name;
        struct stat child_info {};
        const std::string child_full = join_path(root, child);
        if (lstat(child_full.c_str(), &child_info) != 0) {
            std::fprintf(stderr, "lstat failed: %s: %s\n", child.c_str(), std::strerror(errno));
            success = false;
            break;
        }
        if (S_ISDIR(child_info.st_mode) && !collect_directories(root, child, entries)) {
            success = false;
            break;
        }
        errno = 0;
    }
    if (errno != 0) {
        std::fprintf(stderr, "readdir failed: %s\n", std::strerror(errno));
        success = false;
    }
    closedir(directory);
    return success;
}

bool save_manifest(const std::string& output, const std::vector<Entry>& entries) {
    const std::string temporary = output + ".tmp";
    FILE* file = std::fopen(temporary.c_str(), "wb");
    if (file == nullptr) {
        std::fprintf(stderr, "open output failed: %s\n", std::strerror(errno));
        return false;
    }
    const uint64_t count = entries.size();
    bool success = write_exact(file, kMagic, sizeof(kMagic)) &&
        write_exact(file, &kVersion, sizeof(kVersion)) &&
        write_exact(file, &count, sizeof(count));
    for (const Entry& entry : entries) {
        const uint32_t length = static_cast<uint32_t>(entry.path.size());
        success = success &&
            write_exact(file, &entry.seconds, sizeof(entry.seconds)) &&
            write_exact(file, &entry.nanoseconds, sizeof(entry.nanoseconds)) &&
            write_exact(file, &length, sizeof(length)) &&
            write_exact(file, entry.path.data(), length);
        if (!success) break;
    }
    if (std::fflush(file) != 0 || fsync(fileno(file)) != 0) success = false;
    if (std::fclose(file) != 0) success = false;
    if (!success) {
        unlink(temporary.c_str());
        std::fprintf(stderr, "write manifest failed\n");
        return false;
    }
    if (rename(temporary.c_str(), output.c_str()) != 0) {
        std::fprintf(stderr, "rename manifest failed: %s\n", std::strerror(errno));
        unlink(temporary.c_str());
        return false;
    }
    return true;
}

bool load_manifest(const std::string& input, std::vector<Entry>* entries) {
    FILE* file = std::fopen(input.c_str(), "rb");
    if (file == nullptr) {
        std::fprintf(stderr, "open manifest failed: %s\n", std::strerror(errno));
        return false;
    }
    char magic[sizeof(kMagic)] {};
    uint32_t version = 0;
    uint64_t count = 0;
    bool success = read_exact(file, magic, sizeof(magic)) &&
        read_exact(file, &version, sizeof(version)) &&
        read_exact(file, &count, sizeof(count));
    if (!success || std::memcmp(magic, kMagic, sizeof(kMagic)) != 0 || version != kVersion || count > 10000000) {
        std::fprintf(stderr, "invalid manifest header\n");
        std::fclose(file);
        return false;
    }
    entries->reserve(static_cast<size_t>(count));
    for (uint64_t index = 0; index < count; ++index) {
        Entry entry {};
        uint32_t length = 0;
        success = read_exact(file, &entry.seconds, sizeof(entry.seconds)) &&
            read_exact(file, &entry.nanoseconds, sizeof(entry.nanoseconds)) &&
            read_exact(file, &length, sizeof(length));
        if (!success || length == 0 || length > kMaxPathBytes || entry.nanoseconds < 0 || entry.nanoseconds >= 1000000000) {
            success = false;
            break;
        }
        entry.path.resize(length);
        if (!read_exact(file, entry.path.data(), length) || !valid_relative_path(entry.path)) {
            success = false;
            break;
        }
        entries->push_back(std::move(entry));
    }
    if (std::fgetc(file) != EOF) success = false;
    std::fclose(file);
    if (!success) std::fprintf(stderr, "invalid manifest record\n");
    return success;
}

bool canonical_root(const char* input, std::string* output) {
    char* resolved = realpath(input, nullptr);
    if (resolved == nullptr) {
        std::fprintf(stderr, "invalid root: %s\n", std::strerror(errno));
        return false;
    }
    *output = resolved;
    free(resolved);
    struct stat info {};
    if (lstat(output->c_str(), &info) != 0 || !S_ISDIR(info.st_mode)) {
        std::fprintf(stderr, "root is not a directory\n");
        return false;
    }
    while (output->size() > 1 && output->back() == '/') output->pop_back();
    return true;
}

bool target_is_inside_root(const std::string& root, const std::string& relative, std::string* target) {
    const std::string candidate = join_path(root, relative);
    char* resolved = realpath(candidate.c_str(), nullptr);
    if (resolved == nullptr) return false;
    *target = resolved;
    free(resolved);
    return *target == root ||
        (target->size() > root.size() && target->compare(0, root.size(), root) == 0 && (*target)[root.size()] == '/');
}

int snapshot(const char* root_arg, const char* output) {
    std::string root;
    if (!canonical_root(root_arg, &root)) return 2;
    std::vector<Entry> entries;
    if (!collect_directories(root, "", &entries)) return 3;
    if (!save_manifest(output, entries)) return 4;
    std::printf("SNAPSHOT_DIRS=%zu\n", entries.size());
    return 0;
}

int restore(const char* root_arg, const char* input) {
    std::string root;
    if (!canonical_root(root_arg, &root)) return 2;
    std::vector<Entry> entries;
    if (!load_manifest(input, &entries)) return 3;
    std::sort(entries.begin(), entries.end(), [](const Entry& left, const Entry& right) {
        const int left_depth = path_depth(left.path);
        const int right_depth = path_depth(right.path);
        return left_depth != right_depth ? left_depth > right_depth : left.path > right.path;
    });

    size_t restored = 0;
    size_t failed = 0;
    for (const Entry& entry : entries) {
        std::string target;
        if (!target_is_inside_root(root, entry.path, &target)) {
            std::fprintf(stderr, "outside or missing: %s\n", entry.path.c_str());
            ++failed;
            continue;
        }
        struct stat info {};
        if (lstat(target.c_str(), &info) != 0 || !S_ISDIR(info.st_mode)) {
            std::fprintf(stderr, "not a directory: %s\n", entry.path.c_str());
            ++failed;
            continue;
        }
        timespec times[2] {};
        times[0].tv_nsec = UTIME_OMIT;
        times[1].tv_sec = static_cast<time_t>(entry.seconds);
        times[1].tv_nsec = entry.nanoseconds;
        if (utimensat(AT_FDCWD, target.c_str(), times, AT_SYMLINK_NOFOLLOW) != 0) {
            std::fprintf(stderr, "utimensat failed: %s: %s\n", entry.path.c_str(), std::strerror(errno));
            ++failed;
        } else {
            ++restored;
        }
    }
    std::printf("RESTORED=%zu FAILED=%zu\n", restored, failed);
    return failed == 0 ? 0 : 5;
}

int verify(const char* root_arg, const char* input, const char* tolerance_arg) {
    std::string root;
    if (!canonical_root(root_arg, &root)) return 2;
    char* end = nullptr;
    errno = 0;
    const int64_t tolerance = std::strtoll(tolerance_arg, &end, 10);
    if (errno != 0 || end == tolerance_arg || *end != '\0' || tolerance < 0) {
        std::fprintf(stderr, "invalid tolerance\n");
        return 3;
    }
    std::vector<Entry> entries;
    if (!load_manifest(input, &entries)) return 4;
    size_t matched = 0;
    size_t failed = 0;
    for (const Entry& entry : entries) {
        std::string target;
        struct stat info {};
        if (!target_is_inside_root(root, entry.path, &target) || lstat(target.c_str(), &info) != 0 || !S_ISDIR(info.st_mode)) {
            std::fprintf(stderr, "missing: %s\n", entry.path.c_str());
            ++failed;
            continue;
        }
        const int64_t expected = entry.seconds * 1000000000LL + entry.nanoseconds;
        const int64_t actual = static_cast<int64_t>(info.st_mtim.tv_sec) * 1000000000LL + info.st_mtim.tv_nsec;
        const uint64_t difference = expected >= actual
            ? static_cast<uint64_t>(expected - actual)
            : static_cast<uint64_t>(actual - expected);
        if (difference <= static_cast<uint64_t>(tolerance)) {
            ++matched;
        } else {
            std::fprintf(stderr, "mtime mismatch: %s expected=%lld.%09d actual=%lld.%09ld\n",
                entry.path.c_str(), static_cast<long long>(entry.seconds), entry.nanoseconds,
                static_cast<long long>(info.st_mtim.tv_sec), info.st_mtim.tv_nsec);
            ++failed;
        }
    }
    std::printf("MATCHED=%zu FAILED=%zu TOLERANCE_NS=%lld\n", matched, failed, static_cast<long long>(tolerance));
    return failed == 0 ? 0 : 5;
}

void usage() {
    std::fprintf(stderr,
        "syncmeta version\n"
        "syncmeta snapshot ROOT MANIFEST\n"
        "syncmeta restore ROOT MANIFEST\n"
        "syncmeta verify ROOT MANIFEST TOLERANCE_NS\n");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc == 2 && std::strcmp(argv[1], "version") == 0) {
        std::puts("syncmeta 0.1 (arm64-v8a, manifest v1)");
        return 0;
    }
    if (argc == 4 && std::strcmp(argv[1], "snapshot") == 0) return snapshot(argv[2], argv[3]);
    if (argc == 4 && std::strcmp(argv[1], "restore") == 0) return restore(argv[2], argv[3]);
    if (argc == 5 && std::strcmp(argv[1], "verify") == 0) return verify(argv[2], argv[3], argv[4]);
    usage();
    return 1;
}
