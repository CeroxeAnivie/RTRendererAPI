# Network-only exception. Official source export remains read-only.
# Disabled sessions never initialize Streamline; enabled sessions keep local features.
function(_rtr_apply_donut_variant contract_name source_relative expected contract_expected_sha variant_expected_sha operation_expected_count variant_file output_name manifest_file scope)
    set(original "${CMAKE_SOURCE_DIR}/${source_relative}")
    set(patch "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/patches/${contract_name}.patch")
    set(contract "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/patches/${contract_name}.json")
    file(SHA256 "${original}" original_sha)
    if(NOT original_sha STREQUAL expected)
        message(FATAL_ERROR "Network patch requires the exact locked ${source_relative}; got ${original_sha}")
    endif()
    file(READ "${original}" content)
    string(SHA256 read_sha "${content}")
    if(NOT read_sha STREQUAL original_sha)
        string(REPLACE "\n" "\r\n" content "${content}")
        string(SHA256 read_sha "${content}")
    endif()
    if(NOT read_sha STREQUAL original_sha)
        message(FATAL_ERROR "Network policy source read did not preserve exact bytes")
    endif()
    file(READ "${contract}" contract_content)
    string(REPLACE "\r\n" "\n" contract_content "${contract_content}")
    string(SHA256 contract_sha "${contract_content}")
    if(NOT contract_sha STREQUAL contract_expected_sha)
        message(FATAL_ERROR "Authorized network insertion contract differs")
    endif()
    string(JSON operation_count LENGTH "${contract_content}" operations)
    if(NOT operation_count EQUAL operation_expected_count)
        message(FATAL_ERROR "Authorized network insertion count differs for ${source_relative}")
    endif()
    math(EXPR last_operation "${operation_count} - 1")
    foreach(index RANGE 0 ${last_operation})
        string(JSON anchor GET "${contract_content}" operations ${index} anchor)
        string(JSON insertion GET "${contract_content}" operations ${index} insertion)
        string(REPLACE "\n" "\r\n" anchor "${anchor}")
        string(REPLACE "\n" "\r\n" insertion "${insertion}")
        string(FIND "${content}" "${anchor}" anchor_offset)
        if(anchor_offset LESS 0)
            message(FATAL_ERROR "Network insertion ${index} anchor is absent")
        endif()
        string(LENGTH "${anchor}" anchor_size)
        math(EXPR after_anchor "${anchor_offset} + ${anchor_size}")
        string(SUBSTRING "${content}" ${after_anchor} -1 remainder)
        string(FIND "${remainder}" "${anchor}" duplicate_anchor)
        if(NOT duplicate_anchor EQUAL -1)
            message(FATAL_ERROR "Network insertion ${index} anchor is not unique")
        endif()
        string(REPLACE "${anchor}" "${anchor}${insertion}" patched "${content}")
        string(REPLACE "${anchor}${insertion}" "${anchor}" reversed "${patched}")
        if(NOT reversed STREQUAL content)
            message(FATAL_ERROR "Network insertion ${index} is not reversible")
        endif()
        set(content "${patched}")
    endforeach()
    string(SHA256 variant_sha "${content}")
    if(NOT variant_sha STREQUAL variant_expected_sha)
        message(FATAL_ERROR "Network policy variant bytes differ from the authorized scope")
    endif()
    set(output_directory "${CMAKE_BINARY_DIR}/rtrenderer-patches/${output_name}")
    set(variant "${output_directory}/${variant_file}")
    file(MAKE_DIRECTORY "${output_directory}")
    set(write_variant TRUE)
    if(EXISTS "${variant}")
        file(SHA256 "${variant}" existing_sha)
        if(existing_sha STREQUAL variant_sha)
            set(write_variant FALSE)
        endif()
    endif()
    if(write_variant)
        string(REPLACE "\r\n" "\n" write_content "${content}")
        file(CONFIGURE OUTPUT "${variant}" CONTENT "${write_content}" @ONLY NEWLINE_STYLE CRLF)
    endif()
    file(SHA256 "${variant}" written_sha)
    if(NOT written_sha STREQUAL variant_sha)
        message(FATAL_ERROR "Network variant output bytes differ")
    endif()
    get_target_property(sources donut_app SOURCES)
    get_target_property(target_source_directory donut_app SOURCE_DIR)
    set(replacements 0)
    set(selected_sources)
    foreach(source IN LISTS sources)
        # Donut's target_sources entry is relative to its own SOURCE_DIR,
        # although generated vcxproj entries are absolute. Resolve the actual
        # target property and also accept our already-selected variant.
        get_filename_component(resolved_source "${source}" ABSOLUTE
            BASE_DIR "${target_source_directory}")
        if(resolved_source STREQUAL original OR resolved_source STREQUAL variant)
            list(APPEND selected_sources "${variant}")
            math(EXPR replacements "${replacements} + 1")
        else()
            list(APPEND selected_sources "${source}")
        endif()
    endforeach()
    if(NOT replacements EQUAL 1)
        message(FATAL_ERROR "Network patch must replace exactly one donut_app source; got ${replacements}")
    endif()
    set_property(TARGET donut_app PROPERTY SOURCES "${selected_sources}")
    get_filename_component(source_directory "${original}" DIRECTORY)
    set_source_files_properties("${variant}" DIRECTORY "${CMAKE_SOURCE_DIR}/External/Donut"
        PROPERTIES INCLUDE_DIRECTORIES "${source_directory};${RTR_BRIDGE_SOURCE}")
    file(SHA256 "${patch}" patch_sha)
    set(network_fields "")
    if(scope STREQUAL "networkLifecycleOnly")
        set(network_fields "  \"disabledSessionBypassesInitialization\": true,\n  \"localFeatureSelectionUnmodified\": true,\n  \"guaranteesNoNetwork\": false,\n")
    endif()
    file(WRITE "${output_directory}/${manifest_file}"
        "{\n  \"officialSource\": \"${original}\",\n  \"officialSha256\": \"${original_sha}\",\n  \"variantSource\": \"${variant}\",\n  \"variantSha256\": \"${variant_sha}\",\n  \"patch\": \"${patch}\",\n  \"patchSha256\": \"${patch_sha}\",\n  \"contractNormalizedSha256\": \"${contract_sha}\",\n  \"target\": \"donut_app\",\n  \"officialFileVariants\": 1,\n  \"replacedTargetSources\": ${replacements},\n  \"hunks\": ${operation_expected_count},\n  \"${scope}\": true,\n${network_fields}  \"officialExportUnmodified\": true\n}\n")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${original}" "${patch}" "${contract}")
    message(STATUS "Authorized ${contract_name} variant: ${original_sha} -> ${variant_sha}; target sources replaced=1")
endfunction()

function(rtr_apply_authorized_offline_preferences_patch)
    _rtr_apply_donut_variant("streamline-offline-preferences"
        "External/Donut/src/app/streamline/StreamlineIntegration.cpp"
        "60b6756523e5e16a4d08e5b9842a99050048af1340008fbdf338c92377299929"
        "f9df44b2ca8ebf126c1ed24d1888b793f27c4d0315ad9a48f866939986a5e876"
        "7dfbab89cf5fabf480f7fe371af06ba45940dbba70a2cc76c0834632a86f8d1f"
        10 "StreamlineIntegration.cpp" "streamline-offline-preferences" "manifest.json" networkLifecycleOnly)
    _rtr_apply_donut_variant("streamline-offline-device"
        "External/Donut/src/app/dx12/DeviceManager_DX12.cpp"
        "039d933d45ae5fe83a1e67e77fdf826198528f6061a8fcd3258a50359d395193"
        "9039d3e202e7a3f69fa6bceeb6d7e787dc2da96b53d387809ac41426ebca0653"
        "e878fc8101ef16322d7639fa5b76c4a9dee36352bb685bcaa6bc8087a9fc039a"
        1 "DeviceManager_DX12.cpp" "streamline-offline-preferences" "device-manifest.json" networkLifecycleOnly)
    _rtr_apply_donut_variant("runtime-root-lookup" "External/Donut/src/app/ApplicationBase.cpp"
        "f0c32e67aa3ec7c263a0d0248581a0213e0e47a9be9edcc57bc0721cd55bb9fb"
        "f0b1a8f8da28e8d3e9ac3efb70fde6c7e49f07b5a85a0a4fe2c4197be0a85390"
        "b226c10e5f57fe2f07308247fbe30d1aee650f8385ff0a7b4cead516bc9af433"
        2 "ApplicationBase.cpp" "runtime-root-lookup" "runtime-root-manifest.json" runtimeDirectoryOnly)
    _rtr_apply_donut_variant("embedded-window" "External/Donut/src/app/DeviceManager.cpp"
        "2c80c3a189c077be6a48e429e84702b16ebf09f008f44904aa81869ed88805e6"
        "a202da41dbaa8760e587504b82f3fe0249a7e5bab38042faf57df93e543596d6"
        "7a14f018f2e141eedfe97b7374bb1b0b9c21d13025fbac2c50de342a8669c139"
        3 "DeviceManager.cpp" "embedded-window" "manifest.json" windowVisibilityOnly)
endfunction()
