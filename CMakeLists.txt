cmake_minimum_required(VERSION 3.16)
project(klipper_android_bridge VERSION 0.1.0 LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)

add_library(kab_protocol INTERFACE)
target_include_directories(kab_protocol INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/bridge/include)

add_executable(klipper-android-bridge bridge/src/bridge.c bridge/src/config.c)
target_link_libraries(klipper-android-bridge PRIVATE kab_protocol)
target_compile_options(klipper-android-bridge PRIVATE -Wall -Wextra -Wpedantic)

if(APPLE)
  target_compile_definitions(klipper-android-bridge PRIVATE KAB_APPLE=1)
endif()

include(CTest)
if(BUILD_TESTING)
  add_executable(protocol_test bridge/tests/protocol_test.c)
  target_link_libraries(protocol_test PRIVATE kab_protocol)
  target_compile_options(protocol_test PRIVATE -Wall -Wextra -Wpedantic)
  add_test(NAME protocol_test COMMAND protocol_test)
  add_executable(buffer_test bridge/tests/buffer_test.c)
  target_link_libraries(buffer_test PRIVATE kab_protocol)
  target_compile_options(buffer_test PRIVATE -Wall -Wextra -Wpedantic)
  add_test(NAME buffer_test COMMAND buffer_test)
  add_executable(config_test bridge/tests/config_test.c bridge/src/config.c)
  target_link_libraries(config_test PRIVATE kab_protocol)
  target_compile_options(config_test PRIVATE -Wall -Wextra -Wpedantic)
  add_test(NAME config_test COMMAND config_test)
  find_package(Python3 COMPONENTS Interpreter)
  if(Python3_Interpreter_FOUND)
    add_test(NAME bridge_integration
      COMMAND ${Python3_EXECUTABLE}
              ${CMAKE_CURRENT_SOURCE_DIR}/tests/integration/test_bridge.py
              $<TARGET_FILE:klipper-android-bridge>)
    set_tests_properties(bridge_integration PROPERTIES TIMEOUT 15)
  endif()
  add_test(NAME installer_dry_run
    COMMAND ${CMAKE_CURRENT_SOURCE_DIR}/tests/installer/test_installer.sh)
endif()


install(TARGETS klipper-android-bridge RUNTIME DESTINATION bin)
