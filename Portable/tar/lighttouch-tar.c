/*
 * lighttouch-tar: the tar firmwarekit runs on Android (LT_TAR), for the Cydia bootstrap's archives.
 *
 * The tar a phone has (toybox) differs between Android releases and OEMs: some lack options, print -tv in another
 * layout, or fail on this bootstrap (an app can't create hard links, and -k makes it report every file it keeps as
 * an error). This one behaves the same everywhere. It reads plain or gzip tar (ustar prefixes, GNU long names, pax
 * headers) and does only what Packages/FirmwareKit asks of tar:
 *
 *   tar -tf  ARCHIVE                          the member names, one a line
 *   tar -tvf ARCHIVE                          GNU's long listing: "-rwsr-sr-x root/wheel 133808 2017-02-16 02:44 name"
 *   tar -xkf ARCHIVE -C DIR [--exclude PATH]  extract; keep what exists (silently); skip PATH and what is under it
 *
 * A hard link is made with link(2), and where the system refuses it, as a copy of its target. Owners are not
 * restored and set-ID bits are dropped (as a tar that is not root does); a member's modes keep owner access.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <zlib.h>

#define BLOCK 512
#define META_MAX (1u << 20)
#define MAX_EXCLUDES 256

static gzFile archive;
static int failed;
static int verbose, keep;
static const char *excludes[MAX_EXCLUDES];
static int exclude_count;
static char root_real[PATH_MAX];

struct dirlist {
    char **paths;
    size_t count, capacity;
};

struct member {
    char *path;
    char *link;
    char type;
    unsigned mode;
    unsigned long long size;
    long long mtime;
    char owner[64];
};

static void complain(const char *what, const char *name)
{
    fprintf(stderr, "tar: %s%s%s%s%s\n", what, name ? ": " : "", name ? name : "",
            errno ? ": " : "", errno ? strerror(errno) : "");
    failed = 1;
}

static void die(const char *what, const char *name)
{
    complain(what, name);
    exit(2);
}

/* Reads exactly n bytes; returns the count read (short only at the end of the archive). */
static size_t read_all(void *buffer, size_t n)
{
    size_t done = 0;
    while (done < n) {
        int got = gzread(archive, (char *)buffer + done, (unsigned)(n - done > 1u << 20 ? 1u << 20 : n - done));
        if (got < 0) {
            errno = 0;
            die("cannot read the archive", NULL);
        }
        if (got == 0) break;
        done += (size_t)got;
    }
    return done;
}

static void skip(unsigned long long n)
{
    char scratch[8192];
    while (n > 0) {
        size_t want = n > sizeof scratch ? sizeof scratch : (size_t)n;
        if (read_all(scratch, want) != want) {
            errno = 0;
            die("the archive ends inside a member", NULL);
        }
        n -= want;
    }
}

static unsigned long long number(const unsigned char *field, size_t length)
{
    unsigned long long value = 0;
    size_t i = 0;
    if (field[0] & 0x80) { /* GNU's big-endian binary form, for numbers too big for octal */
        value = field[0] & 0x7f;
        for (i = 1; i < length; i++) value = (value << 8) | field[i];
        return value;
    }
    while (i < length && (field[i] == ' ' || field[i] == 0)) i++;
    for (; i < length && field[i] >= '0' && field[i] <= '7'; i++) value = value * 8 + (unsigned)(field[i] - '0');
    return value;
}

static char *copy_text(const unsigned char *bytes, size_t length)
{
    size_t n = 0;
    char *text;
    while (n < length && bytes[n]) n++;
    text = malloc(n + 1);
    if (!text) die("out of memory", NULL);
    memcpy(text, bytes, n);
    text[n] = 0;
    return text;
}

/* The member's path as tar names it for matching: no leading "./", no trailing "/". */
static char *clean(const char *name)
{
    size_t n;
    char *copy;
    while (name[0] == '.' && name[1] == '/') name += 2;
    while (name[0] == '/') name++;
    copy = strdup(name);
    if (!copy) die("out of memory", NULL);
    n = strlen(copy);
    while (n > 0 && copy[n - 1] == '/') copy[--n] = 0;
    if (strcmp(copy, ".") == 0) copy[0] = 0;
    return copy;
}

static int unsafe(const char *path)
{
    const char *p = path;
    while (*p) {
        const char *end = strchr(p, '/');
        size_t n = end ? (size_t)(end - p) : strlen(p);
        if (n == 2 && p[0] == '.' && p[1] == '.') return 1;
        if (!end) break;
        p = end + 1;
    }
    return 0;
}

static int excluded(const char *path)
{
    int i;
    for (i = 0; i < exclude_count; i++) {
        size_t n = strlen(excludes[i]);
        if (n > 0 && strncmp(path, excludes[i], n) == 0 && (path[n] == 0 || path[n] == '/')) return 1;
    }
    return 0;
}

/* Applies a pax extended header's "path", "linkpath" and "size" records to the next member. */
static void pax_records(const unsigned char *data, size_t size, char **path, char **link, long long *override)
{
    size_t at = 0;
    while (at < size) {
        size_t length = 0, i = at;
        const unsigned char *record, *equals;
        while (i < size && data[i] >= '0' && data[i] <= '9') length = length * 10 + (size_t)(data[i++] - '0');
        if (i >= size || data[i] != ' ' || length <= i - at + 1 || at + length > size) return;
        record = data + i + 1;
        equals = memchr(record, '=', at + length - 1 - (size_t)(record - data));
        if (equals) {
            size_t key = (size_t)(equals - record);
            size_t value = at + length - 1 - (size_t)(equals + 1 - data);
            if (key == 4 && memcmp(record, "path", 4) == 0) {
                free(*path);
                *path = copy_text(equals + 1, value);
            } else if (key == 8 && memcmp(record, "linkpath", 8) == 0) {
                free(*link);
                *link = copy_text(equals + 1, value);
            } else if (key == 4 && memcmp(record, "size", 4) == 0) {
                char *text = copy_text(equals + 1, value);
                *override = atoll(text);
                free(text);
            }
        }
        at += length;
    }
}

/*
 * Reads the next member's header, folding in GNU long names and pax headers before it. Returns 0 at the end of the
 * archive. The member's data is still to be read or skipped (member->size bytes, padded to a block).
 */
static int next_member(struct member *m)
{
    unsigned char block[BLOCK];
    char *long_path = NULL, *long_link = NULL, *pax_path = NULL, *pax_link = NULL;
    long long pax_size = -1;

    for (;;) {
        size_t got = read_all(block, BLOCK);
        unsigned long long size;
        unsigned char type;
        size_t i;
        int zero = 1;
        if (got < BLOCK) return 0;
        for (i = 0; i < BLOCK; i++) {
            if (block[i]) {
                zero = 0;
                break;
            }
        }
        if (zero) return 0;
        size = number(block + 124, 12);
        type = block[156];

        if (type == 'L' || type == 'K' || type == 'x') {
            unsigned char *data;
            if (size > META_MAX) {
                errno = 0;
                die("an oversized header", NULL);
            }
            data = malloc((size_t)size + BLOCK);
            if (!data) die("out of memory", NULL);
            {
                size_t padded = (size_t)((size + BLOCK - 1) / BLOCK * BLOCK);
                if (read_all(data, padded) != padded) {
                    errno = 0;
                    die("the archive ends inside a header", NULL);
                }
            }
            if (type == 'L') {
                free(long_path);
                long_path = copy_text(data, (size_t)size);
            } else if (type == 'K') {
                free(long_link);
                long_link = copy_text(data, (size_t)size);
            } else {
                pax_records(data, (size_t)size, &pax_path, &pax_link, &pax_size);
            }
            free(data);
            continue;
        }
        if (type == 'g') {
            skip((size + BLOCK - 1) / BLOCK * BLOCK);
            continue;
        }

        memset(m, 0, sizeof *m);
        m->type = type ? (char)type : '0';
        m->mode = (unsigned)number(block + 100, 8) & 07777;
        m->size = pax_size >= 0 ? (unsigned long long)pax_size : size;
        m->mtime = (long long)number(block + 136, 12);
        if (m->type == '2' || m->type == '5') m->size = 0;
        if (pax_path) {
            m->path = pax_path;
            pax_path = NULL;
        } else if (long_path) {
            m->path = long_path;
            long_path = NULL;
        } else {
            char *name = copy_text(block, 100);
            if (memcmp(block + 257, "ustar", 5) == 0 && block[262] == 0 && block[345]) {
                char *prefix = copy_text(block + 345, 155);
                m->path = malloc(strlen(prefix) + strlen(name) + 2);
                if (!m->path) die("out of memory", NULL);
                sprintf(m->path, "%s/%s", prefix, name);
                free(prefix);
                free(name);
            } else {
                m->path = name;
            }
        }
        if (pax_link) {
            m->link = pax_link;
            pax_link = NULL;
        } else if (long_link) {
            m->link = long_link;
            long_link = NULL;
        } else {
            m->link = copy_text(block + 157, 100);
        }
        {
            char *user = copy_text(block + 265, 32), *group = copy_text(block + 297, 32);
            if (user[0] && group[0])
                snprintf(m->owner, sizeof m->owner, "%s/%s", user, group);
            else
                snprintf(m->owner, sizeof m->owner, "%llu/%llu", number(block + 108, 8), number(block + 116, 8));
            free(user);
            free(group);
        }
        free(long_path);
        free(long_link);
        free(pax_path);
        free(pax_link);
        return 1;
    }
}

static void release(struct member *m)
{
    free(m->path);
    free(m->link);
    m->path = m->link = NULL;
}

static unsigned long long padding(unsigned long long size)
{
    return (BLOCK - size % BLOCK) % BLOCK;
}

/* ---- listing ---- */

static void list_member(const struct member *m)
{
    if (!verbose) {
        printf("%s\n", m->path);
        return;
    }
    {
        char perms[11], letter;
        struct tm tm;
        time_t when = (time_t)m->mtime;
        const char *type_letter = "-dlhcbp";
        const char types[] = "0521346";
        const char *at = m->type == '7' ? types : strchr(types, m->type);
        letter = at ? type_letter[at - types] : '-';
        perms[0] = letter;
        perms[1] = m->mode & 0400 ? 'r' : '-';
        perms[2] = m->mode & 0200 ? 'w' : '-';
        perms[3] = m->mode & 04000 ? (m->mode & 0100 ? 's' : 'S') : (m->mode & 0100 ? 'x' : '-');
        perms[4] = m->mode & 0040 ? 'r' : '-';
        perms[5] = m->mode & 0020 ? 'w' : '-';
        perms[6] = m->mode & 02000 ? (m->mode & 0010 ? 's' : 'S') : (m->mode & 0010 ? 'x' : '-');
        perms[7] = m->mode & 0004 ? 'r' : '-';
        perms[8] = m->mode & 0002 ? 'w' : '-';
        perms[9] = m->mode & 01000 ? (m->mode & 0001 ? 't' : 'T') : (m->mode & 0001 ? 'x' : '-');
        perms[10] = 0;
        gmtime_r(&when, &tm);
        printf("%s %s %llu %04d-%02d-%02d %02d:%02d %s", perms, m->owner, m->size, tm.tm_year + 1900,
               tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, m->path);
        if (m->type == '2') printf(" -> %s", m->link);
        if (m->type == '1') printf(" link to %s", m->link);
        printf("\n");
    }
}

/* ---- extraction ---- */

static int exists(const char *path)
{
    struct stat st;
    return lstat(path, &st) == 0;
}

/* mkdir -p for the parent folders of `path`; 0 when they are there and inside the extraction root. */
static int make_parents(const char *path)
{
    char *copy = strdup(path), *slash;
    char real[PATH_MAX];
    int ok = 0;
    size_t i;
    if (!copy) die("out of memory", NULL);
    for (i = 1; copy[i]; i++) {
        if (copy[i] != '/') continue;
        copy[i] = 0;
        if (mkdir(copy, 0755) != 0 && errno != EEXIST) goto done;
        copy[i] = '/';
    }
    slash = strrchr(copy, '/');
    if (slash) *slash = 0;
    else strcpy(copy, ".");
    if (!realpath(copy, real)) goto done;
    {
        size_t n = strlen(root_real);
        ok = strncmp(real, root_real, n) == 0 && (real[n] == 0 || real[n] == '/');
        if (!ok) errno = EPERM;
    }
done:
    free(copy);
    return ok ? 0 : -1;
}

static void stamp(const char *path, long long mtime)
{
    struct timespec times[2];
    times[0].tv_sec = times[1].tv_sec = (time_t)mtime;
    times[0].tv_nsec = times[1].tv_nsec = 0;
    utimensat(AT_FDCWD, path, times, AT_SYMLINK_NOFOLLOW);
}

static void write_file(const struct member *m, const char *path)
{
    unsigned long long left = m->size;
    char buffer[65536];
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL, 0644);
    if (fd < 0) {
        complain("cannot create", path);
        skip(left);
        return;
    }
    while (left > 0) {
        size_t want = left > sizeof buffer ? sizeof buffer : (size_t)left;
        size_t offset = 0;
        if (read_all(buffer, want) != want) {
            errno = 0;
            die("the archive ends inside a member", path);
        }
        while (offset < want) {
            ssize_t put = write(fd, buffer + offset, want - offset);
            if (put <= 0) {
                complain("cannot write", path);
                close(fd);
                skip(left - want);
                return;
            }
            offset += (size_t)put;
        }
        left -= want;
    }
    fchmod(fd, (mode_t)(m->mode & 0777));
    close(fd);
    stamp(path, m->mtime);
}

static int copy_file(const char *from, const char *to, unsigned mode)
{
    char buffer[65536];
    int in = open(from, O_RDONLY), out;
    ssize_t got;
    if (in < 0) return -1;
    out = open(to, O_WRONLY | O_CREAT | O_EXCL, 0644);
    if (out < 0) {
        close(in);
        return -1;
    }
    while ((got = read(in, buffer, sizeof buffer)) > 0) {
        ssize_t offset = 0;
        while (offset < got) {
            ssize_t put = write(out, buffer + offset, (size_t)(got - offset));
            if (put <= 0) {
                close(in);
                close(out);
                return -1;
            }
            offset += put;
        }
    }
    fchmod(out, (mode_t)(mode & 0777));
    close(in);
    close(out);
    return got < 0 ? -1 : 0;
}

static void extract_member(struct member *m, struct dirlist *dirs)
{
    char *path = clean(m->path);
    unsigned long long data = m->size + padding(m->size);

    if (!path[0] || excluded(path) || unsafe(path)) {
        if (path[0] && unsafe(path)) {
            errno = 0;
            complain("refusing a path that leaves the folder", m->path);
        }
        skip(data);
        free(path);
        return;
    }
    if (make_parents(path) != 0) {
        complain("cannot make the folder for", path);
        skip(data);
        free(path);
        return;
    }
    if (m->type == '5') {
        if (!exists(path)) {
            if (mkdir(path, 0755) != 0) {
                complain("cannot make", path);
            } else if (dirs->count < dirs->capacity) {
                char *saved = malloc(strlen(path) + 12);
                if (saved) {
                    sprintf(saved, "%04o%s", m->mode & 0777, path);
                    dirs->paths[dirs->count++] = saved;
                }
            }
        }
        free(path);
        return;
    }
    if (exists(path)) {
        if (keep) {
            skip(data);
            free(path);
            return;
        }
        unlink(path);
    }
    switch (m->type) {
    case '0':
    case '7':
        write_file(m, path);
        skip(padding(m->size));
        break;
    case '2':
        if (symlink(m->link, path) != 0) complain("cannot link", path);
        skip(data);
        break;
    case '1': {
        char *target = clean(m->link);
        if (!target[0] || unsafe(target)) {
            errno = 0;
            complain("a hard link to a path outside the folder", path);
        } else if (link(target, path) != 0) {
            struct stat st;
            if (stat(target, &st) != 0) {
                complain("a hard link whose target is missing", target);
            } else if (S_ISDIR(st.st_mode)) {
                /* A folder can't be hard-linked; the bootstrap means a link such as ./etc -> private/etc. */
                if (symlink(m->link, path) != 0) complain("cannot link", path);
            } else if (copy_file(target, path, st.st_mode) != 0) {
                complain("cannot copy", path);
            }
        }
        free(target);
        skip(data);
        break;
    }
    default:
        skip(data);
        break;
    }
    free(path);
}

static void usage(void)
{
    fprintf(stderr, "usage: tar -tf|-tvf|-xkf ARCHIVE [-C DIR] [--exclude PATH]...\n");
    exit(2);
}

int main(int argc, char **argv)
{
    const char *file = NULL, *directory = NULL;
    int list = 0, extract = 0, i;
    struct member m;
    struct dirlist dirs = {NULL, 0, 0};

    for (i = 1; i < argc; i++) {
        const char *arg = argv[i];
        if (strncmp(arg, "--exclude=", 10) == 0 || strcmp(arg, "--exclude") == 0) {
            const char *value = arg[9] == '=' ? arg + 10 : (i + 1 < argc ? argv[++i] : NULL);
            if (!value) usage();
            if (exclude_count < MAX_EXCLUDES) excludes[exclude_count++] = clean(value);
        } else if (strcmp(arg, "--keep-old-files") == 0) {
            keep = 1;
        } else if (arg[0] == '-' && arg[1] != '-' && arg[1]) {
            const char *flag;
            for (flag = arg + 1; *flag; flag++) {
                switch (*flag) {
                case 't': list = 1; break;
                case 'x': extract = 1; break;
                case 'v': verbose = 1; break;
                case 'k': keep = 1; break;
                case 'z': break;
                case 'f':
                    if (i + 1 >= argc) usage();
                    file = argv[++i];
                    break;
                case 'C':
                    if (i + 1 >= argc) usage();
                    directory = argv[++i];
                    break;
                default: usage();
                }
            }
        } else {
            usage();
        }
    }
    if (!file || list == extract) usage();

    archive = gzopen(file, "rb");
    if (!archive) die("cannot open", file);
    if (extract) {
        if (directory && chdir(directory) != 0) die("cannot enter", directory);
        if (!realpath(".", root_real)) die("cannot find the folder", NULL);
        dirs.capacity = 65536;
        dirs.paths = calloc(dirs.capacity, sizeof *dirs.paths);
        if (!dirs.paths) die("out of memory", NULL);
    }
    while (next_member(&m)) {
        if (list) {
            list_member(&m);
            skip(m.size + padding(m.size));
        } else {
            extract_member(&m, &dirs);
        }
        release(&m);
    }
    /* A folder made here ends up with its archive mode, owner access kept (deepest first by archive order). */
    for (i = (int)dirs.count - 1; i >= 0; i--) {
        unsigned mode;
        if (sscanf(dirs.paths[i], "%4o", &mode) == 1) chmod(dirs.paths[i] + 4, (mode_t)(mode | 0700));
        free(dirs.paths[i]);
    }
    free(dirs.paths);
    gzclose(archive);
    if (fflush(stdout) != 0) failed = 1;
    return failed ? 2 : 0;
}
