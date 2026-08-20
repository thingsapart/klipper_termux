#ifndef K4A_BUFFER_H
#define K4A_BUFFER_H

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define K4A_BUFFER_SIZE 16384u

struct k4a_buffer {
    uint8_t data[K4A_BUFFER_SIZE];
    size_t begin;
    size_t end;
};

static inline size_t k4a_buffer_readable(const struct k4a_buffer *buffer) {
    return buffer->end - buffer->begin;
}

static inline size_t k4a_buffer_writable(struct k4a_buffer *buffer) {
    if (buffer->begin && buffer->end == K4A_BUFFER_SIZE) {
        size_t length = k4a_buffer_readable(buffer);
        memmove(buffer->data, buffer->data + buffer->begin, length);
        buffer->begin = 0;
        buffer->end = length;
    }
    return K4A_BUFFER_SIZE - buffer->end;
}

static inline uint8_t *k4a_buffer_write_ptr(struct k4a_buffer *buffer) {
    return buffer->data + buffer->end;
}

static inline const uint8_t *k4a_buffer_read_ptr(const struct k4a_buffer *buffer) {
    return buffer->data + buffer->begin;
}

static inline void k4a_buffer_produced(struct k4a_buffer *buffer, size_t amount) {
    buffer->end += amount;
}

static inline void k4a_buffer_consumed(struct k4a_buffer *buffer, size_t amount) {
    buffer->begin += amount;
    if (buffer->begin == buffer->end) {
        buffer->begin = 0;
        buffer->end = 0;
    }
}

static inline void k4a_buffer_clear(struct k4a_buffer *buffer) {
    buffer->begin = 0;
    buffer->end = 0;
}

#endif

