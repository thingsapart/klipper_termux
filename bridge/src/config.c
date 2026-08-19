#define _POSIX_C_SOURCE 200809L

#include "kab_config.h"

#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char *trim(char *text) {
    while (isspace((unsigned char)*text)) {
        text++;
    }
    char *end = text + strlen(text);
    while (end > text && isspace((unsigned char)end[-1])) {
        *--end = 0;
    }
    return text;
}

static int hex_value(char character) {
    if (character >= '0' && character <= '9') return character - '0';
    if (character >= 'a' && character <= 'f') return character - 'a' + 10;
    if (character >= 'A' && character <= 'F') return character - 'A' + 10;
    return -1;
}

int kab_parse_hex(const char *text, uint8_t *output, size_t output_size) {
    size_t output_index = 0;
    int high = -1;
    for (; *text; text++) {
        if (*text == '-' || *text == ':' || isspace((unsigned char)*text)) continue;
        int value = hex_value(*text);
        if (value < 0) return -1;
        if (high < 0) {
            high = value;
        } else {
            if (output_index >= output_size) return -1;
            output[output_index++] = (uint8_t)((high << 4) | value);
            high = -1;
        }
    }
    return high < 0 && output_index == output_size ? 0 : -1;
}

void kab_config_defaults(struct kab_config *config) {
    memset(config, 0, sizeof(*config));
    snprintf(config->host, sizeof(config->host), "127.0.0.1");
    config->port = KAB_DEFAULT_PORT;
    config->log_interval_seconds = 60;
}

static int parse_unsigned(const char *text, unsigned long maximum,
                          unsigned long *result) {
    char *end = NULL;
    errno = 0;
    unsigned long value = strtoul(text, &end, 10);
    if (errno || !end || *trim(end) || value > maximum) return -1;
    *result = value;
    return 0;
}

static int parse_parity(const char *text, uint8_t *parity) {
    if (!strcmp(text, "none")) *parity = KAB_PARITY_NONE;
    else if (!strcmp(text, "odd")) *parity = KAB_PARITY_ODD;
    else if (!strcmp(text, "even")) *parity = KAB_PARITY_EVEN;
    else if (!strcmp(text, "mark")) *parity = KAB_PARITY_MARK;
    else if (!strcmp(text, "space")) *parity = KAB_PARITY_SPACE;
    else return -1;
    return 0;
}

static int parse_flags(const char *text, uint8_t *flags) {
    *flags = 0;
    if (!strcmp(text, "none") || !*text) return 0;
    char copy[32];
    if (strlen(text) >= sizeof(copy)) return -1;
    strcpy(copy, text);
    char *save = NULL;
    for (char *part = strtok_r(copy, "+", &save); part;
         part = strtok_r(NULL, "+", &save)) {
        if (!strcmp(part, "dtr")) *flags |= KAB_FLAG_DTR;
        else if (!strcmp(part, "rts")) *flags |= KAB_FLAG_RTS;
        else return -1;
    }
    return 0;
}

static int parse_device(char *value, struct kab_config *config,
                        char *error, size_t error_size, unsigned line_number) {
    if (config->device_count >= KAB_MAX_DEVICES) {
        snprintf(error, error_size, "line %u: too many devices", line_number);
        return -1;
    }
    char *fields[8] = {0};
    char *save = NULL;
    size_t count = 0;
    for (char *part = strtok_r(value, ",", &save); part && count < 8;
         part = strtok_r(NULL, ",", &save)) {
        fields[count++] = trim(part);
    }
    if (count != 8) {
        snprintf(error, error_size,
                 "line %u: device needs alias,uuid,baud,data,stop,parity,flags,pty",
                 line_number);
        return -1;
    }
    struct kab_device_config *device = &config->devices[config->device_count];
    if (!*fields[0] || strlen(fields[0]) >= sizeof(device->alias) ||
        strlen(fields[7]) >= sizeof(device->pty_link)) {
        snprintf(error, error_size, "line %u: alias or PTY path is invalid", line_number);
        return -1;
    }
    for (size_t index = 0; index < config->device_count; index++) {
        if (!strcmp(fields[0], config->devices[index].alias)) {
            snprintf(error, error_size, "line %u: duplicate alias '%s'", line_number,
                     fields[0]);
            return -1;
        }
    }
    strcpy(device->alias, fields[0]);
    strcpy(device->pty_link, fields[7]);
    if (kab_parse_hex(fields[1], device->device_id, sizeof(device->device_id))) {
        snprintf(error, error_size, "line %u: invalid device UUID", line_number);
        return -1;
    }
    unsigned long number;
    if (parse_unsigned(fields[2], 4000000, &number) || number < 1200) {
        snprintf(error, error_size, "line %u: invalid baud", line_number);
        return -1;
    }
    device->baud = (uint32_t)number;
    if (parse_unsigned(fields[3], 8, &number) || number < 5) {
        snprintf(error, error_size, "line %u: data bits must be 5..8", line_number);
        return -1;
    }
    device->data_bits = (uint8_t)number;
    if (parse_unsigned(fields[4], 2, &number) || number < 1) {
        snprintf(error, error_size, "line %u: stop bits must be 1 or 2", line_number);
        return -1;
    }
    device->stop_bits = (uint8_t)number;
    if (parse_parity(fields[5], &device->parity) ||
        parse_flags(fields[6], &device->flags)) {
        snprintf(error, error_size, "line %u: invalid parity or flags", line_number);
        return -1;
    }
    config->device_count++;
    return 0;
}

int kab_config_load(const char *path, struct kab_config *config,
                    char *error, size_t error_size) {
    kab_config_defaults(config);
    FILE *file = fopen(path, "r");
    if (!file) {
        snprintf(error, error_size, "cannot open %s: %s", path, strerror(errno));
        return -1;
    }
    char line[2048];
    unsigned line_number = 0;
    while (fgets(line, sizeof(line), file)) {
        line_number++;
        if (!strchr(line, '\n') && !feof(file)) {
            snprintf(error, error_size, "line %u is too long", line_number);
            fclose(file);
            return -1;
        }
        char *content = trim(line);
        if (!*content || *content == '#') continue;
        char *equals = strchr(content, '=');
        if (!equals) {
            snprintf(error, error_size, "line %u: expected key=value", line_number);
            fclose(file);
            return -1;
        }
        *equals = 0;
        char *key = trim(content);
        char *value = trim(equals + 1);
        unsigned long number;
        if (!strcmp(key, "server")) {
            if (!*value || strlen(value) >= sizeof(config->host)) goto invalid_value;
            strcpy(config->host, value);
        } else if (!strcmp(key, "port")) {
            if (parse_unsigned(value, 65535, &number) || !number) goto invalid_value;
            config->port = (uint16_t)number;
        } else if (!strcmp(key, "token")) {
            if (kab_parse_hex(value, config->token, sizeof(config->token))) goto invalid_value;
            config->token_set = 1;
        } else if (!strcmp(key, "log_interval")) {
            if (parse_unsigned(value, 86400, &number)) goto invalid_value;
            config->log_interval_seconds = (unsigned)number;
        } else if (!strcmp(key, "device")) {
            if (parse_device(value, config, error, error_size, line_number)) {
                fclose(file);
                return -1;
            }
        } else {
            snprintf(error, error_size, "line %u: unknown key '%s'", line_number, key);
            fclose(file);
            return -1;
        }
        continue;
invalid_value:
        snprintf(error, error_size, "line %u: invalid value for %s", line_number, key);
        fclose(file);
        return -1;
    }
    if (ferror(file)) {
        snprintf(error, error_size, "read %s failed", path);
        fclose(file);
        return -1;
    }
    fclose(file);
    if (!config->token_set) {
        snprintf(error, error_size, "configuration has no token");
        return -1;
    }
    if (!config->device_count) {
        snprintf(error, error_size, "configuration has no devices");
        return -1;
    }
    return 0;
}
