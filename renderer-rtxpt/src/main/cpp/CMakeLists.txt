# Included at the end of the pinned upstream RTXPT configure, preserving its SDK integration.
find_package(JNI REQUIRED COMPONENTS JVM)
add_library(rtrenderer_rtxpt SHARED "${CMAKE_CURRENT_LIST_DIR}/rtxpt_jni.cpp")
target_link_libraries(rtrenderer_rtxpt PRIVATE RtxptCore ${JNI_LIBRARIES})
target_include_directories(rtrenderer_rtxpt PRIVATE ${JNI_INCLUDE_DIRS})
target_compile_definitions(rtrenderer_rtxpt PRIVATE
    RTR_RTXPT_ADVANCED_SOURCE="${CMAKE_SOURCE_DIR}/Rtxpt/AdvancedSample.cpp"
    RTR_RTXPT_VERSION="1.9.0")
set_target_properties(rtrenderer_rtxpt PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_RUNTIME_OUTPUT_DIRECTORY}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_RUNTIME_OUTPUT_DIRECTORY}")
