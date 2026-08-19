#include "kab_protocol.h"

#include <assert.h>
#include <stdio.h>

int main(void) {
    struct kab_open_request request = {0};
    memcpy(request.magic, KAB_MAGIC, sizeof(KAB_MAGIC));
    request.version_be = kab_htobe16(KAB_PROTOCOL_VERSION);
    request.operation_be = kab_htobe16(KAB_OP_OPEN);
    request.request_id_be = kab_htobe32(0x12345678u);
    request.baud_be = kab_htobe32(250000u);
    request.data_bits = 8;
    request.stop_bits = 1;

    assert(sizeof(request) == 72);
    assert(kab_has_magic(request.magic));
    assert(kab_be16toh(request.version_be) == KAB_PROTOCOL_VERSION);
    assert(kab_be16toh(request.operation_be) == KAB_OP_OPEN);
    assert(kab_be32toh(request.request_id_be) == 0x12345678u);
    assert(kab_be32toh(request.baud_be) == 250000u);
    assert(sizeof(struct kab_response) == 20);
    puts("protocol_test: ok");
    return 0;
}
