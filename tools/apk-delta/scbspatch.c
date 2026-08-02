#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef BZIP2_PATH
#define BZIP2_PATH "/system/bin/bzip2"
#endif

#define PATCH_HEADER_SIZE 32
#define BUFFER_SIZE 65536
#define MAX_INPUT_SIZE (256LL * 1024LL * 1024LL)
#define MAX_PATCH_SIZE (256LL * 1024LL * 1024LL)
#define MAX_OUTPUT_SIZE (128LL * 1024LL * 1024LL)
#define MAX_CONTROL_TUPLES 4000000

struct decoder {
    int fd;
    pid_t pid;
};

static int read_exact(int fd, void *buffer, size_t size)
{
    unsigned char *cursor = buffer;
    while (size > 0) {
        ssize_t count = read(fd, cursor, size);
        if (count == 0)
            return -1;
        if (count < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        cursor += count;
        size -= (size_t)count;
    }
    return 0;
}

static int write_exact(int fd, const void *buffer, size_t size)
{
    const unsigned char *cursor = buffer;
    while (size > 0) {
        ssize_t count = write(fd, cursor, size);
        if (count < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (count == 0)
            return -1;
        cursor += count;
        size -= (size_t)count;
    }
    return 0;
}

static int pread_exact(int fd, void *buffer, size_t size, off_t offset)
{
    unsigned char *cursor = buffer;
    while (size > 0) {
        ssize_t count = pread(fd, cursor, size, offset);
        if (count == 0)
            return -1;
        if (count < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        cursor += count;
        offset += count;
        size -= (size_t)count;
    }
    return 0;
}

static int decode_offset(const unsigned char value[8], int64_t *result)
{
    uint64_t magnitude = value[7] & 0x7f;
    int index;
    for (index = 6; index >= 0; --index)
        magnitude = magnitude * 256 + value[index];
    if (magnitude > INT64_MAX)
        return -1;
    *result = (value[7] & 0x80) ? -(int64_t)magnitude : (int64_t)magnitude;
    return 0;
}

static int add_checked(int64_t left, int64_t right, int64_t *result)
{
    if ((right > 0 && left > INT64_MAX - right) ||
        (right < 0 && left < INT64_MIN - right))
        return -1;
    *result = left + right;
    return 0;
}

static int copy_range(int source, int destination, int64_t offset, int64_t length)
{
    unsigned char buffer[BUFFER_SIZE];
    while (length > 0) {
        size_t chunk = length > (int64_t)sizeof(buffer) ? sizeof(buffer) : (size_t)length;
        if (pread_exact(source, buffer, chunk, (off_t)offset) != 0)
            return -1;
        if (write_exact(destination, buffer, chunk) != 0)
            return -1;
        offset += (int64_t)chunk;
        length -= (int64_t)chunk;
    }
    return 0;
}

static int make_slice(int patch_fd, const char *path, int64_t offset, int64_t length)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    int result = -1;
    if (fd < 0)
        return -1;
    if (copy_range(patch_fd, fd, offset, length) == 0 && fsync(fd) == 0)
        result = 0;
    if (close(fd) != 0)
        result = -1;
    if (result != 0)
        unlink(path);
    return result;
}

static int set_cloexec(int fd)
{
    int flags = fcntl(fd, F_GETFD);
    if (flags < 0)
        return -1;
    return fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}

static int start_decoder(const char *path, struct decoder *decoder)
{
    int input = -1;
    int stream[2] = {-1, -1};
    pid_t pid;

    input = open(path, O_RDONLY | O_CLOEXEC);
    if (input < 0 || pipe(stream) != 0 ||
        set_cloexec(stream[0]) != 0 || set_cloexec(stream[1]) != 0) {
        if (input >= 0)
            close(input);
        if (stream[0] >= 0)
            close(stream[0]);
        if (stream[1] >= 0)
            close(stream[1]);
        return -1;
    }
    pid = fork();
    if (pid < 0) {
        close(input);
        close(stream[0]);
        close(stream[1]);
        return -1;
    }
    if (pid == 0) {
        int child_input = fcntl(input, F_DUPFD_CLOEXEC, 3);
        int child_output = fcntl(stream[1], F_DUPFD_CLOEXEC, 3);
        int null_fd = open("/dev/null", O_WRONLY | O_CLOEXEC);
        close(input);
        close(stream[0]);
        close(stream[1]);
        if (child_input < 0 || child_output < 0 || null_fd < 0 ||
            dup2(child_input, STDIN_FILENO) < 0 ||
            dup2(child_output, STDOUT_FILENO) < 0 ||
            dup2(null_fd, STDERR_FILENO) < 0)
            _exit(126);
        close(child_input);
        close(child_output);
        close(null_fd);
        execl(BZIP2_PATH, "bzip2", "-dc", (char *)NULL);
        _exit(127);
    }
    close(input);
    close(stream[1]);
    decoder->fd = stream[0];
    decoder->pid = pid;
    return 0;
}

static int finish_decoder(struct decoder *decoder, int require_eof)
{
    unsigned char byte;
    int status = 0;
    int result = 0;
    if (decoder->fd >= 0) {
        if (require_eof) {
            ssize_t count;
            do {
                count = read(decoder->fd, &byte, 1);
            } while (count < 0 && errno == EINTR);
            if (count != 0)
                result = -1;
        }
        close(decoder->fd);
        decoder->fd = -1;
    }
    if (decoder->pid > 0) {
        while (waitpid(decoder->pid, &status, 0) < 0) {
            if (errno != EINTR) {
                result = -1;
                break;
            }
        }
        if (!WIFEXITED(status) || WEXITSTATUS(status) != 0)
            result = -1;
        decoder->pid = -1;
    }
    return result;
}

static void stop_decoder(struct decoder *decoder)
{
    if (decoder->fd >= 0) {
        close(decoder->fd);
        decoder->fd = -1;
    }
    if (decoder->pid > 0) {
        kill(decoder->pid, SIGTERM);
        while (waitpid(decoder->pid, NULL, 0) < 0 && errno == EINTR) {
        }
        decoder->pid = -1;
    }
}

static int apply_diff(int old_fd, int output_fd, int diff_fd, int64_t old_size,
                      int64_t old_position, int64_t length)
{
    unsigned char diff[BUFFER_SIZE];
    unsigned char old[BUFFER_SIZE];
    int64_t completed = 0;

    while (completed < length) {
        int64_t remaining = length - completed;
        size_t chunk = remaining > (int64_t)sizeof(diff) ? sizeof(diff) : (size_t)remaining;
        int64_t base;
        size_t valid_start = 0;
        size_t valid_end = chunk;
        size_t index;

        if (read_exact(diff_fd, diff, chunk) != 0 ||
            add_checked(old_position, completed, &base) != 0)
            return -1;
        if (base < 0) {
            uint64_t missing = (uint64_t)(-(base + 1)) + 1;
            valid_start = missing >= chunk ? chunk : (size_t)missing;
        }
        if (base >= old_size) {
            valid_end = 0;
        } else if (base >= 0 && old_size - base < (int64_t)chunk) {
            valid_end = (size_t)(old_size - base);
        } else if (base < 0) {
            int64_t end_position;
            if (add_checked(base, (int64_t)chunk, &end_position) != 0)
                return -1;
            if (end_position > old_size)
                valid_end = (size_t)(old_size - base);
        }
        if (valid_end > chunk)
            valid_end = chunk;
        if (valid_start < valid_end) {
            int64_t read_offset;
            if (add_checked(base, (int64_t)valid_start, &read_offset) != 0 ||
                read_offset < 0 || read_offset > old_size ||
                pread_exact(old_fd, old + valid_start, valid_end - valid_start,
                            (off_t)read_offset) != 0)
                return -1;
            for (index = valid_start; index < valid_end; ++index)
                diff[index] = (unsigned char)(diff[index] + old[index]);
        }
        if (write_exact(output_fd, diff, chunk) != 0)
            return -1;
        completed += (int64_t)chunk;
    }
    return 0;
}

static int copy_stream(int source, int destination, int64_t length)
{
    unsigned char buffer[BUFFER_SIZE];
    while (length > 0) {
        size_t chunk = length > (int64_t)sizeof(buffer) ? sizeof(buffer) : (size_t)length;
        if (read_exact(source, buffer, chunk) != 0 ||
            write_exact(destination, buffer, chunk) != 0)
            return -1;
        length -= (int64_t)chunk;
    }
    return 0;
}

int main(int argc, char **argv)
{
    unsigned char header[PATCH_HEADER_SIZE];
    unsigned char control[24];
    struct stat patch_stat;
    struct stat old_stat;
    struct decoder decoders[3] = {{-1, -1}, {-1, -1}, {-1, -1}};
    char slices[3][PATH_MAX];
    int patch_fd = -1;
    int old_fd = -1;
    int output_fd = -1;
    int output_created = 0;
    int result = 1;
    int64_t control_length;
    int64_t diff_length;
    int64_t new_size;
    int64_t extra_offset;
    int64_t old_position = 0;
    int64_t new_position = 0;
    int64_t control_count = 0;
    int index;

    if (argc != 4) {
        fprintf(stderr, "usage: %s OLD NEW PATCH\n", argv[0]);
        return 2;
    }
    for (index = 0; index < 3; ++index) {
        if (snprintf(slices[index], sizeof(slices[index]), "%s.%ld.%d.bz2",
                     argv[2], (long)getpid(), index) >= (int)sizeof(slices[index])) {
            fprintf(stderr, "temporary path is too long\n");
            return 2;
        }
    }

    patch_fd = open(argv[3], O_RDONLY | O_CLOEXEC);
    old_fd = open(argv[1], O_RDONLY | O_CLOEXEC);
    if (patch_fd < 0 || old_fd < 0 ||
        fstat(patch_fd, &patch_stat) != 0 || fstat(old_fd, &old_stat) != 0 ||
        !S_ISREG(patch_stat.st_mode) || !S_ISREG(old_stat.st_mode) ||
        patch_stat.st_size < PATCH_HEADER_SIZE ||
        patch_stat.st_size > MAX_PATCH_SIZE ||
        old_stat.st_size <= 0 || old_stat.st_size > MAX_INPUT_SIZE ||
        pread_exact(patch_fd, header, sizeof(header), 0) != 0 ||
        memcmp(header, "BSDIFF40", 8) != 0 ||
        decode_offset(header + 8, &control_length) != 0 ||
        decode_offset(header + 16, &diff_length) != 0 ||
        decode_offset(header + 24, &new_size) != 0 ||
        control_length < 0 || diff_length < 0 || new_size <= 0 ||
        new_size > MAX_OUTPUT_SIZE ||
        add_checked(PATCH_HEADER_SIZE, control_length, &extra_offset) != 0 ||
        add_checked(extra_offset, diff_length, &extra_offset) != 0 ||
        extra_offset > patch_stat.st_size) {
        fprintf(stderr, "invalid BSDIFF40 patch\n");
        goto cleanup;
    }
    if (make_slice(patch_fd, slices[0], PATCH_HEADER_SIZE, control_length) != 0 ||
        make_slice(patch_fd, slices[1], PATCH_HEADER_SIZE + control_length, diff_length) != 0 ||
        make_slice(patch_fd, slices[2], extra_offset, patch_stat.st_size - extra_offset) != 0) {
        fprintf(stderr, "failed to stage compressed patch streams\n");
        goto cleanup;
    }
    for (index = 0; index < 3; ++index) {
        if (start_decoder(slices[index], &decoders[index]) != 0) {
            fprintf(stderr, "failed to start bzip2 decoder\n");
            goto cleanup;
        }
    }
    output_fd = open(argv[2], O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (output_fd < 0) {
        fprintf(stderr, "failed to create output\n");
        goto cleanup;
    }
    output_created = 1;

    while (new_position < new_size) {
        int64_t diff_count;
        int64_t extra_count;
        int64_t seek_adjustment;
        int64_t next_position;

        if (read_exact(decoders[0].fd, control, sizeof(control)) != 0 ||
            decode_offset(control, &diff_count) != 0 ||
            decode_offset(control + 8, &extra_count) != 0 ||
            decode_offset(control + 16, &seek_adjustment) != 0) {
            fprintf(stderr, "invalid control stream at output offset %lld\n",
                    (long long)new_position);
            goto cleanup;
        }
        ++control_count;
        if (control_count > MAX_CONTROL_TUPLES ||
            diff_count < 0 || extra_count < 0 ||
            (diff_count == 0 && extra_count == 0 && seek_adjustment == 0) ||
            add_checked(new_position, diff_count, &next_position) != 0 ||
            next_position > new_size) {
            fprintf(stderr, "invalid control tuple at output offset %lld\n",
                    (long long)new_position);
            goto cleanup;
        }
        if (apply_diff(old_fd, output_fd, decoders[1].fd, old_stat.st_size,
                       old_position, diff_count) != 0) {
            fprintf(stderr, "invalid diff stream at output offset %lld\n",
                    (long long)new_position);
            goto cleanup;
        }
        new_position = next_position;
        if (add_checked(old_position, diff_count, &old_position) != 0 ||
            add_checked(new_position, extra_count, &next_position) != 0 ||
            next_position > new_size ||
            copy_stream(decoders[2].fd, output_fd, extra_count) != 0 ||
            add_checked(old_position, seek_adjustment, &old_position) != 0) {
            fprintf(stderr, "invalid extra stream at output offset %lld\n",
                    (long long)new_position);
            goto cleanup;
        }
        new_position = next_position;
    }
    if (fsync(output_fd) != 0 || close(output_fd) != 0) {
        output_fd = -1;
        fprintf(stderr, "failed to commit output\n");
        goto cleanup;
    }
    output_fd = -1;
    for (index = 0; index < 3; ++index) {
        if (finish_decoder(&decoders[index], 1) != 0) {
            fprintf(stderr, "corrupt compressed stream\n");
            goto cleanup;
        }
    }
    result = 0;

cleanup:
    if (output_fd >= 0)
        close(output_fd);
    if (result != 0 && output_created)
        unlink(argv[2]);
    for (index = 0; index < 3; ++index) {
        stop_decoder(&decoders[index]);
        unlink(slices[index]);
    }
    if (old_fd >= 0)
        close(old_fd);
    if (patch_fd >= 0)
        close(patch_fd);
    return result;
}
