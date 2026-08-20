#ifndef K4A_PROTOCOL_H
#define K4A_PROTOCOL_H

#include <stdint.h>
#include <string.h>

#define K4A_PROTOCOL_VERSION 1u
#define K4A_TOKEN_SIZE 32u
#define K4A_DEVICE_ID_SIZE 16u
#define K4A_MAX_MESSAGE_SIZE 1024u
#define K4A_DEFAULT_PORT 27831u

static const uint8_t K4A_MAGIC[8] = {'K', 'L', 'I', 'P', 'U', 'S', 'B', 0};

enum k4a_operation {
    K4A_OP_OPEN = 1,
    K4A_OP_LIST = 2,
    K4A_OP_STATUS = 3,
};

enum k4a_status {
    K4A_STATUS_OK = 0,
    K4A_STATUS_BAD_MAGIC = 1,
    K4A_STATUS_UNSUPPORTED_VERSION = 2,
    K4A_STATUS_UNAUTHORIZED = 3,
    K4A_STATUS_BAD_REQUEST = 4,
    K4A_STATUS_DEVICE_NOT_FOUND = 5,
    K4A_STATUS_PERMISSION_REQUIRED = 6,
    K4A_STATUS_DEVICE_BUSY = 7,
    K4A_STATUS_USB_ERROR = 8,
    K4A_STATUS_INTERNAL_ERROR = 9,
};

enum k4a_parity {
    K4A_PARITY_NONE = 0,
    K4A_PARITY_ODD = 1,
    K4A_PARITY_EVEN = 2,
    K4A_PARITY_MARK = 3,
    K4A_PARITY_SPACE = 4,
};

enum k4a_open_flags {
    K4A_FLAG_DTR = 1u << 0,
    K4A_FLAG_RTS = 1u << 1,
};

#pragma pack(push, 1)
struct k4a_open_request {
    uint8_t magic[8];
    uint16_t version_be;
    uint16_t operation_be;
    uint32_t request_id_be;
    uint8_t token[K4A_TOKEN_SIZE];
    uint8_t device_id[K4A_DEVICE_ID_SIZE];
    uint32_t baud_be;
    uint8_t data_bits;
    uint8_t stop_bits;
    uint8_t parity;
    uint8_t flags;
};

struct k4a_response {
    uint8_t magic[8];
    uint16_t version_be;
    uint16_t status_be;
    uint32_t request_id_be;
    uint16_t message_length_be;
    uint16_t reserved;
};
#pragma pack(pop)

_Static_assert(sizeof(struct k4a_open_request) == 72, "protocol request size changed");
_Static_assert(sizeof(struct k4a_response) == 20, "protocol response size changed");

static inline uint16_t k4a_bswap16(uint16_t value) {
    return (uint16_t)((value << 8) | (value >> 8));
}

static inline uint32_t k4a_bswap32(uint32_t value) {
    return ((value & 0x000000ffu) << 24) |
           ((value & 0x0000ff00u) << 8) |
           ((value & 0x00ff0000u) >> 8) |
           ((value & 0xff000000u) >> 24);
}

#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
#define k4a_htobe16(v) ((uint16_t)(v))
#define k4a_be16toh(v) ((uint16_t)(v))
#define k4a_htobe32(v) ((uint32_t)(v))
#define k4a_be32toh(v) ((uint32_t)(v))
#else
#define k4a_htobe16(v) k4a_bswap16((uint16_t)(v))
#define k4a_be16toh(v) k4a_bswap16((uint16_t)(v))
#define k4a_htobe32(v) k4a_bswap32((uint32_t)(v))
#define k4a_be32toh(v) k4a_bswap32((uint32_t)(v))
#endif

static inline int k4a_has_magic(const uint8_t magic[8]) {
    return memcmp(magic, K4A_MAGIC, sizeof(K4A_MAGIC)) == 0;
}

#endif

