#ifndef K4A_CONFIG_H
#define K4A_CONFIG_H

#include "k4a_protocol.h"

#include <stddef.h>
#include <stdint.h>

#define K4A_MAX_DEVICES 16u
#define K4A_ALIAS_SIZE 64u
#define K4A_PATH_SIZE 512u
#define K4A_HOST_SIZE 256u

struct k4a_device_config {
    char alias[K4A_ALIAS_SIZE];
    uint8_t device_id[K4A_DEVICE_ID_SIZE];
    int online;
    uint32_t baud;
    uint8_t data_bits;
    uint8_t stop_bits;
    uint8_t parity;
    uint8_t flags;
    char pty_link[K4A_PATH_SIZE];
};

struct k4a_config {
    char host[K4A_HOST_SIZE];
    uint16_t port;
    uint8_t token[K4A_TOKEN_SIZE];
    int token_set;
    unsigned log_interval_seconds;
    size_t device_count;
    struct k4a_device_config devices[K4A_MAX_DEVICES];
};

void k4a_config_defaults(struct k4a_config *config);
int k4a_parse_hex(const char *text, uint8_t *output, size_t output_size);
int k4a_config_load(const char *path, struct k4a_config *config,
                    char *error, size_t error_size);

#endif
