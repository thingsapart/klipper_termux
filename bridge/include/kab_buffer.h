#ifndef KAB_BUFFER_H
#define KAB_BUFFER_H

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define KAB_BUFFER_SIZE 16384u

struct kab_buffer {
    uint8_t data[KAB_BUFFER_SIZE];
    size_t begin;
    size_t end;
};

static inline size_t kab_buffer_readable(const struct kab_buffer *buffer) {
    return buffer->end - buffer->begin;
}

static inline size_t kab_buffer_writable(struct kab_buffer *buffer) {
    if (buffer->begin && buffer->end == KAB_BUFFER_SIZE) {
        size_t length = kab_buffer_readable(buffer);
        memmove(buffer->data, buffer->data + buffer->begin, length);
        buffer->begin = 0;
        buffer->end = length;
    }
    return KAB_BUFFER_SIZE - buffer->end;
}

static inline uint8_t *kab_buffer_write_ptr(struct kab_buffer *buffer) {
    return buffer->data + buffer->end;
}

static inline const uint8_t *kab_buffer_read_ptr(const struct kab_buffer *buffer) {
    return buffer->data + buffer->begin;
}

static inline void kab_buffer_produced(struct kab_buffer *buffer, size_t amount) {
    buffer->end += amount;
}

static inline void kab_buffer_consumed(struct kab_buffer *buffer, size_t amount) {
    buffer->begin += amount;
    if (buffer->begin == buffer->end) {
        buffer->begin = 0;
        buffer->end = 0;
    }
}

static inline void kab_buffer_clear(struct kab_buffer *buffer) {
    buffer->begin = 0;
    buffer->end = 0;
}

#endif

