#include "k4a_protocol.h"

#include <assert.h>
#include <stdio.h>

int main(void) {
    struct k4a_open_request request = {0};
    memcpy(request.magic, K4A_MAGIC, sizeof(K4A_MAGIC));
    request.version_be = k4a_htobe16(K4A_PROTOCOL_VERSION);
    request.operation_be = k4a_htobe16(K4A_OP_OPEN);
    request.request_id_be = k4a_htobe32(0x12345678u);
    request.baud_be = k4a_htobe32(250000u);
    request.data_bits = 8;
    request.stop_bits = 1;

    assert(sizeof(request) == 72);
    assert(k4a_has_magic(request.magic));
    assert(k4a_be16toh(request.version_be) == K4A_PROTOCOL_VERSION);
    assert(k4a_be16toh(request.operation_be) == K4A_OP_OPEN);
    assert(k4a_be32toh(request.request_id_be) == 0x12345678u);
    assert(k4a_be32toh(request.baud_be) == 250000u);
    assert(sizeof(struct k4a_response) == 20);
    puts("protocol_test: ok");
    return 0;
}
