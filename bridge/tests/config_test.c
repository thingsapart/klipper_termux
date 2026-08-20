#define _POSIX_C_SOURCE 200809L
#include "kab_config.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(void) {
    uint8_t bytes[4];
    assert(kab_parse_hex("01-ab:CD ef", bytes, sizeof(bytes)) == 0);
    assert(bytes[0] == 0x01 && bytes[1] == 0xab &&
           bytes[2] == 0xcd && bytes[3] == 0xef);
    assert(kab_parse_hex("abc", bytes, sizeof(bytes)) != 0);

    char path[] = "/tmp/kab-config-test-XXXXXX";
    int descriptor = mkstemp(path);
    assert(descriptor >= 0);
    FILE *file = fdopen(descriptor, "w");
    assert(file);
    fputs("server=127.0.0.1\n"
          "port=27831\n"
          "token=000102030405060708090a0b0c0d0e0f"
          "101112131415161718191a1b1c1d1e1f\n"
          "device=main,00112233-4455-6677-8899-aabbccddeeff,250000,8,1,none,dtr+rts,/tmp/mcu\n"
          "device=auto,auto,250000,8,1,none,dtr+rts,/tmp/auto-mcu\n"
          "device=test,offline,250000,8,1,none,none,/tmp/offline-mcu\n",
          file);
    assert(fclose(file) == 0);

    struct kab_config config;
    char error[256];
    assert(kab_config_load(path, &config, error, sizeof(error)) == 0);
    assert(config.port == 27831);
    assert(config.device_count == 3);
    assert(!strcmp(config.devices[0].alias, "main"));
    assert(config.devices[0].online);
    assert(config.devices[0].baud == 250000);
    assert(config.devices[0].flags == (KAB_FLAG_DTR | KAB_FLAG_RTS));
    assert(config.devices[0].device_id[0] == 0x00);
    assert(config.devices[0].device_id[15] == 0xff);
    assert(config.devices[1].online);
    for (size_t index = 0; index < sizeof(config.devices[1].device_id); index++)
        assert(config.devices[1].device_id[index] == 0);
    assert(!config.devices[2].online);
    unlink(path);
    puts("config_test: ok");
    return 0;
}
