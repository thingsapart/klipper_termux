#ifndef KAB_PROTOCOL_H
#define KAB_PROTOCOL_H

#include <stdint.h>
#include <string.h>

#define KAB_PROTOCOL_VERSION 1u
#define KAB_TOKEN_SIZE 32u
#define KAB_DEVICE_ID_SIZE 16u
#define KAB_MAX_MESSAGE_SIZE 1024u
#define KAB_DEFAULT_PORT 27831u

static const uint8_t KAB_MAGIC[8] = {'K', 'L', 'I', 'P', 'U', 'S', 'B', 0};

enum kab_operation {
    KAB_OP_OPEN = 1,
    KAB_OP_LIST = 2,
    KAB_OP_STATUS = 3,
};

enum kab_status {
    KAB_STATUS_OK = 0,
    KAB_STATUS_BAD_MAGIC = 1,
    KAB_STATUS_UNSUPPORTED_VERSION = 2,
    KAB_STATUS_UNAUTHORIZED = 3,
    KAB_STATUS_BAD_REQUEST = 4,
    KAB_STATUS_DEVICE_NOT_FOUND = 5,
    KAB_STATUS_PERMISSION_REQUIRED = 6,
    KAB_STATUS_DEVICE_BUSY = 7,
    KAB_STATUS_USB_ERROR = 8,
    KAB_STATUS_INTERNAL_ERROR = 9,
};

enum kab_parity {
    KAB_PARITY_NONE = 0,
    KAB_PARITY_ODD = 1,
    KAB_PARITY_EVEN = 2,
    KAB_PARITY_MARK = 3,
    KAB_PARITY_SPACE = 4,
};

enum kab_open_flags {
    KAB_FLAG_DTR = 1u << 0,
    KAB_FLAG_RTS = 1u << 1,
};

#pragma pack(push, 1)
struct kab_open_request {
    uint8_t magic[8];
    uint16_t version_be;
    uint16_t operation_be;
    uint32_t request_id_be;
    uint8_t token[KAB_TOKEN_SIZE];
    uint8_t device_id[KAB_DEVICE_ID_SIZE];
    uint32_t baud_be;
    uint8_t data_bits;
    uint8_t stop_bits;
    uint8_t parity;
    uint8_t flags;
};

struct kab_response {
    uint8_t magic[8];
    uint16_t version_be;
    uint16_t status_be;
    uint32_t request_id_be;
    uint16_t message_length_be;
    uint16_t reserved;
};
#pragma pack(pop)

_Static_assert(sizeof(struct kab_open_request) == 72, "protocol request size changed");
_Static_assert(sizeof(struct kab_response) == 20, "protocol response size changed");

static inline uint16_t kab_bswap16(uint16_t value) {
    return (uint16_t)((value << 8) | (value >> 8));
}

static inline uint32_t kab_bswap32(uint32_t value) {
    return ((value & 0x000000ffu) << 24) |
           ((value & 0x0000ff00u) << 8) |
           ((value & 0x00ff0000u) >> 8) |
           ((value & 0xff000000u) >> 24);
}

#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
#define kab_htobe16(v) ((uint16_t)(v))
#define kab_be16toh(v) ((uint16_t)(v))
#define kab_htobe32(v) ((uint32_t)(v))
#define kab_be32toh(v) ((uint32_t)(v))
#else
#define kab_htobe16(v) kab_bswap16((uint16_t)(v))
#define kab_be16toh(v) kab_bswap16((uint16_t)(v))
#define kab_htobe32(v) kab_bswap32((uint32_t)(v))
#define kab_be32toh(v) kab_bswap32((uint32_t)(v))
#endif

static inline int kab_has_magic(const uint8_t magic[8]) {
    return memcmp(magic, KAB_MAGIC, sizeof(KAB_MAGIC)) == 0;
}

#endif

