# External adapter plus the two independently audited UI/network exceptions.
include("${CMAKE_CURRENT_LIST_DIR}/../../../../tools/RtxptProgressPatch.cmake")
rtr_apply_authorized_progress_patch()
include("${CMAKE_CURRENT_LIST_DIR}/../../../../tools/RtxptOfflinePatch.cmake")
rtr_apply_authorized_offline_preferences_patch()
find_package(JNI REQUIRED COMPONENTS JVM)
add_library(rtrenderer_rtxpt SHARED "${CMAKE_CURRENT_LIST_DIR}/rtxpt_upstream_jni.cpp")
target_link_libraries(rtrenderer_rtxpt PRIVATE RtxptCore d3d12 dxgi)
if(MSVC)
    target_compile_options(rtrenderer_rtxpt PRIVATE /bigobj)
    # Donut explicitly loads the signed SL DLL only when its session needs it.
    # Standard delay imports keep unused independent-lighting sessions offline.
    target_link_options(rtrenderer_rtxpt PRIVATE "/DELAYLOAD:sl.interposer.dll")
    target_link_libraries(rtrenderer_rtxpt PRIVATE delayimp)
endif()
target_include_directories(rtrenderer_rtxpt PRIVATE ${JNI_INCLUDE_DIRS})
target_compile_definitions(rtrenderer_rtxpt PRIVATE
    RTR_RTXPT_ADVANCED_SOURCE="${CMAKE_SOURCE_DIR}/Rtxpt/AdvancedSample.cpp")
add_executable(rtrenderer_jvm_host
    "${CMAKE_CURRENT_LIST_DIR}/rtxpt_jvm_host.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/rtxpt_jvm_host.manifest")
target_include_directories(rtrenderer_jvm_host PRIVATE ${JNI_INCLUDE_DIRS})
target_compile_features(rtrenderer_jvm_host PRIVATE cxx_std_20)
add_executable(rtr_resource_protocol_selftest EXCLUDE_FROM_ALL
    "${CMAKE_CURRENT_LIST_DIR}/rtxpt_resource_protocol_selftest.cpp")
target_compile_features(rtr_resource_protocol_selftest PRIVATE cxx_std_20)
target_link_libraries(rtr_resource_protocol_selftest PRIVATE d3d12 dxgi)
add_executable(rtr_frame_batch_selftest EXCLUDE_FROM_ALL
    "${CMAKE_CURRENT_LIST_DIR}/rtxpt_frame_batch_selftest.cpp")
target_compile_features(rtr_frame_batch_selftest PRIVATE cxx_std_20)
target_link_libraries(rtr_frame_batch_selftest PRIVATE RtxptCore)
set_target_properties(rtr_frame_batch_selftest PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/adapter-tests"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/adapter-tests")
set_target_properties(rtr_resource_protocol_selftest PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/adapter-tests"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/adapter-tests")
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
