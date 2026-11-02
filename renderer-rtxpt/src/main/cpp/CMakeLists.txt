# Included at the end of the pinned upstream RTXPT configure, preserving its SDK integration.
find_package(JNI REQUIRED COMPONENTS JVM)
target_include_directories(RtxptCore PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
target_include_directories(donut_app PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
target_include_directories(donut_engine PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
target_include_directories(nvrhi_d3d12 PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
# Keep embedded-host symbols separate from previous standalone sample builds.
if(MSVC)
    foreach(host_tool ShaderMake ShaderMakeBlob NRD)
        set_target_properties(${host_tool} PROPERTIES
            PDB_NAME "${host_tool}-host"
            COMPILE_PDB_NAME "${host_tool}-host")
    endforeach()
endif()
add_library(rtrenderer_rtxpt SHARED
    "${CMAKE_CURRENT_LIST_DIR}/rtxpt_jni.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/rtxpt_material_policy.cpp")
add_executable(rtxpt_native_control "${CMAKE_CURRENT_LIST_DIR}/rtxpt_native_control.cpp")
add_executable(rtxpt_streamline_cache_selftest EXCLUDE_FROM_ALL
    "${CMAKE_CURRENT_LIST_DIR}/rtxpt_streamline_cache_selftest.cpp")
add_executable(rtxpt_memory_diagnostics_selftest EXCLUDE_FROM_ALL
    "${CMAKE_CURRENT_LIST_DIR}/rtxpt_memory_diagnostics_selftest.cpp")
target_compile_features(rtxpt_memory_diagnostics_selftest PRIVATE cxx_std_17)
target_link_libraries(rtxpt_memory_diagnostics_selftest PRIVATE RtxptCore d3d12 dxgi)
target_compile_features(rtxpt_streamline_cache_selftest PRIVATE cxx_std_17)
target_link_libraries(rtxpt_native_control PRIVATE RtxptCore d3d12)
target_include_directories(rtxpt_native_control PRIVATE "${CMAKE_SOURCE_DIR}/External/Donut/src/app/streamline")
target_compile_definitions(rtxpt_native_control PRIVATE RTR_RTXPT_ADVANCED_SOURCE="${CMAKE_SOURCE_DIR}/Rtxpt/AdvancedSample.cpp")
set_target_properties(rtxpt_native_control PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_RUNTIME_OUTPUT_DIRECTORY}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_RUNTIME_OUTPUT_DIRECTORY}")
# Agility selection is resolved relative to the hosting process executable.
target_link_libraries(rtrenderer_rtxpt PRIVATE RtxptCore ${JNI_LIBRARIES} d3d12 dxgi)
target_include_directories(rtrenderer_rtxpt PRIVATE ${JNI_INCLUDE_DIRS}
    "${CMAKE_SOURCE_DIR}/External/Donut/src/app/streamline")
target_compile_definitions(rtrenderer_rtxpt PRIVATE
    RTR_RTXPT_ADVANCED_SOURCE="${CMAKE_SOURCE_DIR}/Rtxpt/AdvancedSample.cpp"
    RTR_RTXPT_VERSION="1.9.0")
set_target_properties(rtrenderer_rtxpt PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_RUNTIME_OUTPUT_DIRECTORY}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_RUNTIME_OUTPUT_DIRECTORY}")
if (RTXPT_D3D_AGILITY_SDK_PATH AND RTXPT_D3D_AGILITY_SDK_VERSION)
    target_include_directories(rtrenderer_rtxpt PRIVATE ${RTXPT_D3D_AGILITY_SDK_PATH}/build/native/include)
    target_compile_definitions(rtrenderer_rtxpt PRIVATE
        NVRHI_D3D12_WITH_DXR12_OPACITY_MICROMAP=$<BOOL:${NVRHI_D3D12_WITH_DXR12_OPACITY_MICROMAP}>)
    add_custom_command(TARGET rtrenderer_rtxpt POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory "$<TARGET_FILE_DIR:rtrenderer_rtxpt>/D3D12/"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${RTXPT_D3D_AGILITY_SDK_PATH}/build/native/bin/${NVRHI_TARGET_ARCH}/D3D12Core.dll
            "$<TARGET_FILE_DIR:rtrenderer_rtxpt>/D3D12/")
endif()
