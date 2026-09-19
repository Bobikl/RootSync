#include <algorithm>
#include <array>
#include <stdexcept>
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
        if (name == ".rootsync-history" || name == ".rsync-partial") continue;
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

bool collect_files_in_range(
    const std::string& root,
    const std::string& relative,
    int64_t since_millis,
    int64_t until_millis,
    std::vector<std::string>* files
) {
    const std::string full = join_path(root, relative);
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
        if (name == ".rootsync-history" || name == ".rsync-partial") continue;
        const std::string child = relative.empty() ? name : relative + "/" + name;
        const std::string child_full = join_path(root, child);
        struct stat info {};
        if (lstat(child_full.c_str(), &info) != 0) {
            std::fprintf(stderr, "lstat failed: %s: %s\n", child.c_str(), std::strerror(errno));
            success = false;
            break;
        }
        if (S_ISDIR(info.st_mode)) {
            if (!collect_files_in_range(root, child, since_millis, until_millis, files)) {
                success = false;
                break;
            }
        } else if (S_ISREG(info.st_mode) || S_ISLNK(info.st_mode)) {
            const int64_t modified_millis =
                static_cast<int64_t>(info.st_mtim.tv_sec) * 1000LL + info.st_mtim.tv_nsec / 1000000L;
            if (modified_millis >= since_millis && modified_millis <= until_millis) {
                files->push_back(child);
            }
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

bool parse_nonnegative_millis(const char* value, int64_t* output) {
    char* end = nullptr;
    errno = 0;
    const long long parsed = std::strtoll(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || parsed < 0) return false;
    *output = static_cast<int64_t>(parsed);
    return true;
}

bool canonical_root(const char* input, std::string* output);

int filelist(
    const char* root_arg,
    const char* output,
    const char* since_arg,
    const char* until_arg
) {
    std::string root;
    if (!canonical_root(root_arg, &root)) return 2;
    int64_t since_millis = 0;
    int64_t until_millis = 0;
    if (!parse_nonnegative_millis(since_arg, &since_millis) ||
        !parse_nonnegative_millis(until_arg, &until_millis) ||
        since_millis > until_millis) {
        std::fprintf(stderr, "invalid time range\n");
        return 3;
    }
    std::vector<std::string> files;
    if (!collect_files_in_range(root, "", since_millis, until_millis, &files)) return 4;
    std::sort(files.begin(), files.end());

    const std::string temporary = std::string(output) + ".tmp";
    FILE* file = std::fopen(temporary.c_str(), "wb");
    if (file == nullptr) {
        std::fprintf(stderr, "open file list failed: %s\n", std::strerror(errno));
        return 5;
    }
    bool success = true;
    for (const std::string& path : files) {
        success = write_exact(file, path.data(), path.size()) && std::fputc('\0', file) != EOF;
        if (!success) break;
    }
    if (std::fflush(file) != 0 || fsync(fileno(file)) != 0) success = false;
    if (std::fclose(file) != 0) success = false;
    if (!success) {
        unlink(temporary.c_str());
        std::fprintf(stderr, "write file list failed\n");
        return 6;
    }
    if (rename(temporary.c_str(), output) != 0) {
        std::fprintf(stderr, "rename file list failed: %s\n", std::strerror(errno));
        unlink(temporary.c_str());
        return 7;
    }
    std::printf("FILELIST_ITEMS=%zu\n", files.size());
    return 0;
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


// Directory scan protocol v1 (independent of snapshot RSMETA1):
// "RSMAN1\0\0", LE u32 version=1, u32 flags (1=missing,2=strict), u64 count.
// Each: u8 kind (1=dir,2=file,3=link), u64 size, i64 mtime seconds,
// u32 nanos, u32 path bytes, u32 target bytes, u8 hash bytes (0 or 32),
// then UTF-8 path, UTF-8 target, raw SHA256. No root entry.
// Limits also enforced by DirectoryManifest.kt. Output is a trusted runtime path,
// never within ROOT. Caller must chown root-created 0600 output before app reads it.
constexpr uint64_t dm_max_bytes = 64ULL * 1024 * 1024;
constexpr uint64_t dm_max_text = 16ULL * 1024 * 1024;
constexpr size_t dm_max_entries = 200000;
constexpr size_t dm_max_path = 16384;

void dm_require(bool ok, const char* reason) {
    if (!ok) throw std::runtime_error(reason);
}

bool dm_utf8(const std::string& s) {
    size_t i = 0;
    while (i < s.size()) {
        uint32_t c = static_cast<unsigned char>(s[i++]);
        if (c < 128) { if (c == 0) return false; continue; }
        unsigned n; uint32_t min;
        if (c >= 0xc2 && c <= 0xdf) { n=1; min=0x80; c &= 31; }
        else if (c >= 0xe0 && c <= 0xef) { n=2; min=0x800; c &= 15; }
        else if (c >= 0xf0 && c <= 0xf4) { n=3; min=0x10000; c &= 7; }
        else return false;
        while (n--) {
            if (i == s.size()) return false;
            unsigned b = static_cast<unsigned char>(s[i++]);
            if ((b & 0xc0) != 0x80) return false;
            c = (c << 6) | (b & 63);
        }
        if (c < min || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) return false;
    }
    return true;
}

// FIPS 180-4 SHA-256: 64-byte blocks, big-endian words, 64 rounds.
struct DmSha256 {
    uint32_t h[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
                     0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    unsigned char block[64] {};
    size_t used = 0;
    uint64_t bytes = 0;
    static uint32_t r(uint32_t x, unsigned n) { return (x >> n) | (x << (32-n)); }
    void compress() {
        static constexpr uint32_t k[64] = {
            0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
            0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
            0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
            0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
        uint32_t w[64];
        for (unsigned i=0;i<16;++i)
            w[i]=(uint32_t(block[i*4])<<24)|(uint32_t(block[i*4+1])<<16)|
                 (uint32_t(block[i*4+2])<<8)|block[i*4+3];
        for (unsigned i=16;i<64;++i) {
            uint32_t a=w[i-15], b=w[i-2];
            w[i]=w[i-16]+(r(a,7)^r(a,18)^(a>>3))+w[i-7]+(r(b,17)^r(b,19)^(b>>10));
        }
        uint32_t a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],v=h[7];
        for (unsigned i=0;i<64;++i) {
            uint32_t t1=v+(r(e,6)^r(e,11)^r(e,25))+((e&f)^((~e)&g))+k[i]+w[i];
            uint32_t t2=(r(a,2)^r(a,13)^r(a,22))+((a&b)^(a&c)^(b&c));
            v=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
        }
        h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=d;h[4]+=e;h[5]+=f;h[6]+=g;h[7]+=v;
    }
    void update(const unsigned char* p, size_t n) {
        dm_require(n <= UINT64_MAX/8 - bytes, "SHA256 input too large");
        bytes += n;
        while (n) {
            size_t take=std::min(n,64-used);
            std::memcpy(block+used,p,take); used+=take;p+=take;n-=take;
            if (used==64) { compress();used=0; }
        }
    }
    std::array<unsigned char,32> finish() {
        uint64_t bits=bytes*8;
        block[used++]=0x80;
        if (used>56) { std::memset(block+used,0,64-used);compress();used=0; }
        std::memset(block+used,0,56-used);
        for(unsigned i=0;i<8;++i) block[63-i]=static_cast<unsigned char>(bits>>(i*8));
        compress();
        std::array<unsigned char,32> result {};
        for(unsigned i=0;i<32;++i) result[i]=static_cast<unsigned char>(h[i/4]>>(24-8*(i%4)));
        return result;
    }
};
struct DmFd {
    int fd;
    explicit DmFd(int value): fd(value) {}
    ~DmFd() { if(fd>=0) close(fd); }
    DmFd(const DmFd&)=delete;
    DmFd& operator=(const DmFd&)=delete;
};
struct DmDir {
    DIR* p;
    explicit DmDir(int fd): p(fdopendir(fd)) { if(!p && fd>=0) close(fd); }
    ~DmDir() { if(p) closedir(p); }
};
struct DmEntry {
    std::string path, target;
    unsigned char kind;
    struct stat info {};
    std::array<unsigned char,32> hash {};
};
bool dm_same(const struct stat& a, const struct stat& b) {
    return a.st_dev==b.st_dev && a.st_ino==b.st_ino && a.st_mode==b.st_mode &&
        a.st_size==b.st_size && a.st_mtim.tv_sec==b.st_mtim.tv_sec &&
        a.st_mtim.tv_nsec==b.st_mtim.tv_nsec && a.st_ctim.tv_sec==b.st_ctim.tv_sec &&
        a.st_ctim.tv_nsec==b.st_ctim.tv_nsec;
}
bool dm_less(const std::string& a, const std::string& b) {
    return std::lexicographical_compare(a.begin(),a.end(),b.begin(),b.end(),
        [](unsigned char x,unsigned char y){return x<y;});
}
struct DmScan {
    bool strict;
    uint64_t text_bytes=0, wire_bytes=24;
    std::vector<DmEntry> entries;
    void walk(int fd, const std::string& relative, unsigned depth) {
        dm_require(depth<=256,"manifest directory depth exceeds 256");
        struct stat before {}, after {};
        dm_require(fstat(fd,&before)==0,"directory fstat failed");
        DmDir dir(dup(fd));
        dm_require(dir.p!=nullptr,"directory open failed");
        while (true) {
            errno=0;
            dirent* item=readdir(dir.p);
            if (!item) { dm_require(errno==0,"directory read failed"); break; }
            std::string name=item->d_name;
            if(name=="." || name==".." || name==".rsync-partial" || name==".rootsync-history") continue;
            dm_require(entries.size()<dm_max_entries,"manifest exceeds 200000 entries");
            DmEntry e {};
            e.path=relative.empty()?name:relative+"/"+name;
            dm_require(e.path.size()<=dm_max_path && dm_utf8(e.path),"path too long or invalid UTF-8");
            dm_require(fstatat(fd,name.c_str(),&e.info,AT_SYMLINK_NOFOLLOW)==0,"entry lstat failed");
            dm_require(e.info.st_size>=0 && e.info.st_mtim.tv_nsec>=0 &&
                       e.info.st_mtim.tv_nsec<1000000000,"invalid stat metadata");
            if(S_ISDIR(e.info.st_mode)) e.kind=1;
            else if(S_ISREG(e.info.st_mode)) e.kind=2;
            else if(S_ISLNK(e.info.st_mode)) e.kind=3;
            else throw std::runtime_error("unsupported file type");
            if(e.kind==3) {
                std::array<char,dm_max_path+1> target {};
                ssize_t n=readlinkat(fd,name.c_str(),target.data(),target.size());
                dm_require(n>0 && static_cast<size_t>(n)<=dm_max_path,"readlink failed or target too long");
                e.target.assign(target.data(),static_cast<size_t>(n));
                dm_require(dm_utf8(e.target),"symlink target invalid UTF-8");
            }
            if(e.kind==2 && strict) {
                DmFd input(openat(fd,name.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK));
                dm_require(input.fd>=0,"file open failed");
                struct stat opened {};
                dm_require(fstat(input.fd,&opened)==0 && dm_same(e.info,opened),"file changed before hash");
                DmSha256 sha;
                std::array<unsigned char,65536> buffer {};
                uint64_t total=0;
                while(true) {
                    ssize_t n=read(input.fd,buffer.data(),buffer.size());
                    if(n<0 && errno==EINTR) continue;
                    dm_require(n>=0,"file read failed");
                    if(n==0) break;
                    total+=static_cast<uint64_t>(n);
                    dm_require(total<=static_cast<uint64_t>(e.info.st_size),"file grew while hashing");
                    sha.update(buffer.data(),static_cast<size_t>(n));
                }
                dm_require(total==static_cast<uint64_t>(e.info.st_size) &&
                    fstat(input.fd,&opened)==0 && dm_same(e.info,opened),"file changed during hash");
                e.hash=sha.finish();
                dm_require(close(input.fd)==0,"file close failed"); input.fd=-1;
            }
            struct stat current {};
            dm_require(fstatat(fd,name.c_str(),&current,AT_SYMLINK_NOFOLLOW)==0 &&
                dm_same(e.info,current),"entry changed during scan");
            text_bytes+=e.path.size()+e.target.size();
            wire_bytes+=30+e.path.size()+e.target.size()+((strict && e.kind==2)?32:0);
            dm_require(text_bytes<=dm_max_text && wire_bytes<=dm_max_bytes,"manifest exceeds byte budget");
            entries.push_back(e);
            if(entries.size()%256==0) {
                std::printf("MANIFEST_PROGRESS=%zu\n",entries.size()); std::fflush(stdout);
            }
            if(e.kind==1) {
                DmFd child(openat(fd,name.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));
                dm_require(child.fd>=0 && fstat(child.fd,&current)==0 &&
                    dm_same(e.info,current),"directory changed before open");
                walk(child.fd,e.path,depth+1);
                dm_require(fstatat(fd,name.c_str(),&current,AT_SYMLINK_NOFOLLOW)==0 &&
                    dm_same(e.info,current),"directory replaced during scan");
            }
        }
        dm_require(fstat(fd,&after)==0 && dm_same(before,after),"directory changed during scan");
        DIR* raw=dir.p;dir.p=nullptr;
        dm_require(closedir(raw)==0,"directory close failed");
    }
};
void dm_le(FILE* f,uint64_t value,unsigned n) {
    unsigned char bytes[8];
    for(unsigned i=0;i<n;++i) bytes[i]=static_cast<unsigned char>(value>>(8*i));
    dm_require(write_exact(f,bytes,n),"manifest write failed");
}
std::string dm_absolute(const char* input) {
    std::string s=input;
    dm_require(!s.empty(),"empty root");
    if(s.front()!='/') {
        char* cwd=getcwd(nullptr,0);dm_require(cwd!=nullptr,"getcwd failed");
        s=std::string(cwd)+"/"+s;free(cwd);
    }
    std::vector<std::string> parts;
    size_t start=1;
    while(start<=s.size()) {
        size_t end=s.find('/',start);
        std::string part=s.substr(start,end==std::string::npos?end:end-start);
        if(part=="..") { if(!parts.empty()) parts.pop_back(); }
        else if(!part.empty() && part!=".") parts.push_back(part);
        if(end==std::string::npos) break;
        start=end+1;
    }
    std::string result;
    for(const auto& p:parts) result+="/"+p;
    return result.empty()?"/":result;
}
bool dm_inside(const std::string& root,const std::string& path) {
    return root=="/" || root==path ||
        (path.size()>root.size() && path.compare(0,root.size(),root)==0 && path[root.size()]=='/');
}
int directory_manifest(const char* root_arg,const char* output_arg,const char* strict_arg) {
    std::string output,temporary;
    FILE* file=nullptr;
    bool invalidated=false;
    try {
        // Resolve output parent once and reject any output inside the user tree.
        std::string out=output_arg;
        size_t slash=out.find_last_of('/');
        std::string parent=slash==std::string::npos?".":(slash==0?"/":out.substr(0,slash));
        std::string base=slash==std::string::npos?out:out.substr(slash+1);
        dm_require(!base.empty() && base!="." && base!="..","invalid output filename");
        char* resolved=realpath(parent.c_str(),nullptr);
        dm_require(resolved!=nullptr,"output runtime directory missing or inaccessible");
        parent=resolved;free(resolved);
        output=parent+"/"+base;
        std::string root_input=root_arg;
        while(root_input.size()>1 && root_input.back()=='/') root_input.pop_back();
        root_arg=root_input.c_str();
        std::string root=dm_absolute(root_arg);
        struct stat root_info {};
        int root_result=lstat(root_arg,&root_info);
        int root_error=errno;
        bool missing=root_result!=0 && root_error==ENOENT;
        bool root_resolved = root_result != 0;
        if(root_result==0) {
            resolved=realpath(root_arg,nullptr);
            if(resolved) { root=resolved;free(resolved);root_resolved=true; }
        }
        dm_require(!dm_inside(root,parent),"output must be outside scanned root");
        // Remove a prior success BEFORE any scan/argument failure. Never follow output links.
        dm_require(unlink(output.c_str())==0 || errno==ENOENT,"cannot invalidate old manifest");
        invalidated=true;
        dm_require(std::strcmp(strict_arg,"0")==0 || std::strcmp(strict_arg,"1")==0,"STRICT must be 0 or 1");
        dm_require(root_result==0 || missing,"root lstat failed (not ENOENT)");
        dm_require(root_resolved,"root resolution failed");
        bool strict=std::strcmp(strict_arg,"1")==0;
        DmScan scan {strict,0,24,{}};
        if(!missing) {
            dm_require(S_ISDIR(root_info.st_mode),"root is not a real directory");
            DmFd input(open(root_arg,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));
            struct stat current {};
            dm_require(input.fd>=0 && fstat(input.fd,&current)==0 &&
                dm_same(root_info,current),"root changed before open");
            scan.walk(input.fd,"",0);
            dm_require(lstat(root_arg,&current)==0 && dm_same(root_info,current),"root changed during scan");
        }
        std::sort(scan.entries.begin(),scan.entries.end(),
            [](const DmEntry& a,const DmEntry& b){return dm_less(a.path,b.path);});
        temporary=output+".tmp.XXXXXX";
        int fd=mkstemp(temporary.data());
        dm_require(fd>=0,"cannot create temporary manifest");
        file=fdopen(fd,"wb");
        if(!file) { close(fd); throw std::runtime_error("fdopen failed"); }
        const char magic[8]={'R','S','M','A','N','1',0,0};
        dm_require(write_exact(file,magic,8),"manifest header write failed");
        dm_le(file,1,4);dm_le(file,(missing?1:0)|(strict?2:0),4);dm_le(file,scan.entries.size(),8);
        for(const auto& e:scan.entries) {
            unsigned hash_size=strict && e.kind==2?32:0;
            dm_le(file,e.kind,1);dm_le(file,e.info.st_size,8);
            dm_le(file,static_cast<uint64_t>(e.info.st_mtim.tv_sec),8);
            dm_le(file,e.info.st_mtim.tv_nsec,4);
            dm_le(file,e.path.size(),4);dm_le(file,e.target.size(),4);dm_le(file,hash_size,1);
            dm_require(write_exact(file,e.path.data(),e.path.size()) &&
                write_exact(file,e.target.data(),e.target.size()) &&
                write_exact(file,e.hash.data(),hash_size),"manifest record write failed");
        }
        dm_require(std::fflush(file)==0 && fsync(fileno(file))==0,"manifest flush failed");
        FILE* closing=file;file=nullptr;
        dm_require(std::fclose(closing)==0,"manifest close failed");
        dm_require(rename(temporary.c_str(),output.c_str())==0,"manifest atomic rename failed");
        DmFd parent_fd(open(parent.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC));
        dm_require(parent_fd.fd>=0 && fsync(parent_fd.fd)==0,"manifest parent fsync failed");
        std::printf("MANIFEST_ITEMS=%zu MISSING_ROOT=%d STRICT_HASHES=%d\n",scan.entries.size(),missing,strict);
        return 0;
    } catch(const std::exception& e) {
        if(file) std::fclose(file);
        if(!temporary.empty()) unlink(temporary.c_str());
        if(invalidated) unlink(output.c_str());
        std::fprintf(stderr,"manifest failed: %s (errno=%d)\n",e.what(),errno);
        return 8;
    }
}


void usage() {
    std::fprintf(stderr,
        "syncmeta version\n"
        "syncmeta manifest ROOT OUTPUT STRICT\n"
        "syncmeta snapshot ROOT MANIFEST\n"
        "syncmeta restore ROOT MANIFEST\n"
        "syncmeta verify ROOT MANIFEST TOLERANCE_NS\n"
        "syncmeta filelist ROOT OUTPUT SINCE_EPOCH_MS UNTIL_EPOCH_MS\n");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc == 5 && std::strcmp(argv[1], "manifest") == 0) return directory_manifest(argv[2], argv[3], argv[4]);
    if (argc == 2 && std::strcmp(argv[1], "version") == 0) {
        std::puts("syncmeta 0.2 (arm64-v8a, manifest v1, time filelist)");
        return 0;
    }
    if (argc == 4 && std::strcmp(argv[1], "snapshot") == 0) return snapshot(argv[2], argv[3]);
    if (argc == 4 && std::strcmp(argv[1], "restore") == 0) return restore(argv[2], argv[3]);
    if (argc == 5 && std::strcmp(argv[1], "verify") == 0) return verify(argv[2], argv[3], argv[4]);
    if (argc == 6 && std::strcmp(argv[1], "filelist") == 0) {
        return filelist(argv[2], argv[3], argv[4], argv[5]);
    }
    usage();
    return 1;
}
