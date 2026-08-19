#ifndef KAB_CONFIG_H
#define KAB_CONFIG_H

#include "kab_protocol.h"

#include <stddef.h>
#include <stdint.h>

#define KAB_MAX_DEVICES 16u
#define KAB_ALIAS_SIZE 64u
#define KAB_PATH_SIZE 512u
#define KAB_HOST_SIZE 256u

struct kab_device_config {
    char alias[KAB_ALIAS_SIZE];
    uint8_t device_id[KAB_DEVICE_ID_SIZE];
    uint32_t baud;
    uint8_t data_bits;
    uint8_t stop_bits;
    uint8_t parity;
    uint8_t flags;
    char pty_link[KAB_PATH_SIZE];
};

struct kab_config {
    char host[KAB_HOST_SIZE];
    uint16_t port;
    uint8_t token[KAB_TOKEN_SIZE];
    int token_set;
    unsigned log_interval_seconds;
    size_t device_count;
    struct kab_device_config devices[KAB_MAX_DEVICES];
};

void kab_config_defaults(struct kab_config *config);
int kab_parse_hex(const char *text, uint8_t *output, size_t output_size);
int kab_config_load(const char *path, struct kab_config *config,
                    char *error, size_t error_size);

#endif

