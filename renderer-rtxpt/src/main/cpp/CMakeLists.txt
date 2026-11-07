# External adapter plus the single authorized noninteractive progress-window fix.
include("${CMAKE_CURRENT_LIST_DIR}/../../../../tools/RtxptProgressPatch.cmake")
rtr_apply_authorized_progress_patch()
find_package(JNI REQUIRED COMPONENTS JVM)
add_library(rtrenderer_rtxpt SHARED "${CMAKE_CURRENT_LIST_DIR}/rtxpt_upstream_jni.cpp")
target_link_libraries(rtrenderer_rtxpt PRIVATE RtxptCore d3d12 dxgi)
target_include_directories(rtrenderer_rtxpt PRIVATE ${JNI_INCLUDE_DIRS})
target_compile_definitions(rtrenderer_rtxpt PRIVATE
    RTR_RTXPT_ADVANCED_SOURCE="${CMAKE_SOURCE_DIR}/Rtxpt/AdvancedSample.cpp")
add_executable(rtrenderer_jvm_host
    "${CMAKE_CURRENT_LIST_DIR}/rtxpt_jvm_host.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/rtxpt_jvm_host.manifest")
target_include_directories(rtrenderer_jvm_host PRIVATE ${JNI_INCLUDE_DIRS})
target_compile_features(rtrenderer_jvm_host PRIVATE cxx_std_20)
if(RTXPT_D3D_AGILITY_SDK_VERSION)
    target_compile_definitions(rtrenderer_jvm_host PRIVATE
        RTXPT_D3D_AGILITY_SDK_VERSION=${RTXPT_D3D_AGILITY_SDK_VERSION})
endif()
if(RTR_RUNTIME_DIRECTORY)
    foreach(target rtrenderer_rtxpt rtrenderer_jvm_host)
        set_target_properties(${target} PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY "${RTR_RUNTIME_DIRECTORY}"
            RUNTIME_OUTPUT_DIRECTORY_RELEASE "${RTR_RUNTIME_DIRECTORY}")
    endforeach()
endif()
