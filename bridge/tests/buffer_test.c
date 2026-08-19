#include "kab_buffer.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    struct kab_buffer buffer = {0};
    assert(kab_buffer_writable(&buffer) == KAB_BUFFER_SIZE);
    memcpy(kab_buffer_write_ptr(&buffer), "abcdef", 6);
    kab_buffer_produced(&buffer, 6);
    assert(kab_buffer_readable(&buffer) == 6);
    kab_buffer_consumed(&buffer, 4);
    assert(kab_buffer_readable(&buffer) == 2);
    assert(!memcmp(kab_buffer_read_ptr(&buffer), "ef", 2));

    buffer.begin = KAB_BUFFER_SIZE - 2;
    buffer.end = KAB_BUFFER_SIZE;
    memcpy(buffer.data + buffer.begin, "ef", 2);
    assert(kab_buffer_writable(&buffer) == KAB_BUFFER_SIZE - 2);
    assert(buffer.begin == 0 && buffer.end == 2);
    assert(!memcmp(buffer.data, "ef", 2));
    kab_buffer_consumed(&buffer, 2);
    assert(buffer.begin == 0 && buffer.end == 0);
    puts("buffer_test: ok");
    return 0;
}
