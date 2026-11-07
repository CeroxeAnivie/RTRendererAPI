# CMAKE_PROJECT_RTXPathTracing_INCLUDE runs after the original project() call.
# Defer our external target declarations until the original project defines its targets.
# The external declaration selects one hash-locked, build-side SampleCommon
# variant for the authorized noninteractive progress guard. All other upstream
# sources, shaders, compile definitions and policies stay original.
if(NOT RTR_BRIDGE_SOURCE)
    message(FATAL_ERROR "RTR_BRIDGE_SOURCE must identify the external API bridge")
endif()
# CMake disallows add_subdirectory while processing deferred calls; include keeps
# the target declarations external without creating a directory scope or patch.
cmake_language(DEFER DIRECTORY "${CMAKE_SOURCE_DIR}" CALL include
    "${RTR_BRIDGE_SOURCE}/CMakeLists.txt")
