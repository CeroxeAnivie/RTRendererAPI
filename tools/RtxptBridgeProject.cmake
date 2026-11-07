# CMAKE_PROJECT_RTXPathTracing_INCLUDE runs after the original project() call.
# Defer our external target declarations until the original project defines its targets.
# The external declaration selects two hash-locked, build-side source variants:
# SampleCommon's noninteractive progress guard and Donut's offline preferences.
# Rendering sources/shaders/algorithms stay original. Tracy uses its public option.
if(NOT RTR_BRIDGE_SOURCE)
    message(FATAL_ERROR "RTR_BRIDGE_SOURCE must identify the external API bridge")
endif()
# CMake disallows add_subdirectory while processing deferred calls; include keeps
# the target declarations external without creating a directory scope or patch.
cmake_language(DEFER DIRECTORY "${CMAKE_SOURCE_DIR}" CALL include
    "${RTR_BRIDGE_SOURCE}/CMakeLists.txt")
