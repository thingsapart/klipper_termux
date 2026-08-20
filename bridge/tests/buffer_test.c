#include "k4a_buffer.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    struct k4a_buffer buffer = {0};
    assert(k4a_buffer_writable(&buffer) == K4A_BUFFER_SIZE);
    memcpy(k4a_buffer_write_ptr(&buffer), "abcdef", 6);
    k4a_buffer_produced(&buffer, 6);
    assert(k4a_buffer_readable(&buffer) == 6);
    k4a_buffer_consumed(&buffer, 4);
    assert(k4a_buffer_readable(&buffer) == 2);
    assert(!memcmp(k4a_buffer_read_ptr(&buffer), "ef", 2));

    buffer.begin = K4A_BUFFER_SIZE - 2;
    buffer.end = K4A_BUFFER_SIZE;
    memcpy(buffer.data + buffer.begin, "ef", 2);
    assert(k4a_buffer_writable(&buffer) == K4A_BUFFER_SIZE - 2);
    assert(buffer.begin == 0 && buffer.end == 2);
    assert(!memcmp(buffer.data, "ef", 2));
    k4a_buffer_consumed(&buffer, 2);
    assert(buffer.begin == 0 && buffer.end == 0);
    puts("buffer_test: ok");
    return 0;
}
