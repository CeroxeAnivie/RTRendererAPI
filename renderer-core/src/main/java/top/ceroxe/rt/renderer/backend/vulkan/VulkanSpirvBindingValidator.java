package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.BindingKey;
import top.ceroxe.rt.renderer.api.BindingLayoutEntry;
import top.ceroxe.rt.renderer.api.BindingType;
import top.ceroxe.rt.renderer.api.ShaderModule;
import top.ceroxe.rt.renderer.api.ShaderStage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates the descriptor interface embedded in one SPIR-V module before Vulkan pipeline creation.
 *
 * <p>The public reflection declaration is intentionally not trusted by itself. Vulkan descriptor
 * layouts are part of shader binary compatibility, so accepting a declaration that differs from
 * the binary can otherwise create a pipeline whose binding contract is false. This parser covers
 * the descriptor forms expressible by the public API and rejects variable descriptor arrays or
 * unsupported storage classes rather than guessing a layout.</p>
 */
final class VulkanSpirvBindingValidator {
    private static final int HEADER_BYTES = 20;
    private static final int OP_ENTRY_POINT = 15;
    private static final int OP_DECORATE = 71;
    private static final int OP_TYPE_INT = 21;
    private static final int OP_TYPE_IMAGE = 25;
    private static final int OP_TYPE_SAMPLER = 26;
    private static final int OP_TYPE_SAMPLED_IMAGE = 27;
    private static final int OP_TYPE_ARRAY = 28;
    private static final int OP_TYPE_RUNTIME_ARRAY = 29;
    private static final int OP_TYPE_STRUCT = 30;
    private static final int OP_TYPE_POINTER = 32;
    private static final int OP_CONSTANT = 43;
    private static final int OP_VARIABLE = 59;
    private static final int DECORATION_NON_WRITABLE = 24;
    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;
    private static final int STORAGE_UNIFORM_CONSTANT = 0;
    private static final int STORAGE_UNIFORM = 2;
    private static final int STORAGE_STORAGE_BUFFER = 12;
    private static final int IMAGE_SAMPLED = 1;
    private static final int IMAGE_STORAGE = 2;

    private VulkanSpirvBindingValidator() {
    }

    static void requireDeclaredInterface(ShaderModule module) {
        ShaderModule checked = Objects.requireNonNull(module, "module");
        ParsedModule parsed = parse(checked.spirv());
        if (!parsed.entryPoints().contains(new EntryPoint(checked.entryPoint(), executionModel(checked.stage())))) {
            throw new VulkanGenericPipelineCompilationException(
                    "SPIR-V entry point/stage does not match declared module: " + checked.id()
            );
        }

        Map<BindingKey, ReflectedBinding> actual = new LinkedHashMap<>();
        for (Map.Entry<Integer, Variable> variableEntry : parsed.variables().entrySet()) {
            int variableId = variableEntry.getKey();
            Integer set = parsed.descriptorSets().get(variableId);
            Integer binding = parsed.bindings().get(variableId);
            if (set == null && binding == null) continue;
            if (set == null || binding == null) {
                throw unsupported("descriptor variable has only one of DescriptorSet/Binding decorations", checked);
            }
            BindingKey key;
            try {
                key = new BindingKey(set, binding);
            } catch (IllegalArgumentException invalid) {
                throw unsupported("descriptor uses an invalid set/binding location", checked, invalid);
            }
            ReflectedBinding reflected = resolve(variableEntry.getValue(), variableId, parsed, checked);
            if (actual.putIfAbsent(key, reflected) != null) {
                throw unsupported("SPIR-V declares duplicate descriptor location " + key, checked);
            }
        }

        Map<BindingKey, BindingLayoutEntry> expected = new LinkedHashMap<>();
        for (BindingLayoutEntry entry : checked.reflection().bindings()) {
            expected.put(entry.key(), entry);
        }
        if (!actual.keySet().equals(expected.keySet())) {
            throw unsupported("declared bindings do not exactly match SPIR-V descriptor locations", checked);
        }
        for (Map.Entry<BindingKey, ReflectedBinding> actualEntry : actual.entrySet()) {
            BindingLayoutEntry declared = expected.get(actualEntry.getKey());
            ReflectedBinding reflected = actualEntry.getValue();
            if (!compatible(declared.type(), reflected.type()) || declared.arrayCount() != reflected.arrayCount()) {
                throw unsupported("declared binding does not match SPIR-V descriptor at " + actualEntry.getKey(), checked);
            }
        }
    }

    private static ParsedModule parse(ByteBuffer bytes) {
        ByteBuffer source = Objects.requireNonNull(bytes, "bytes");
        // ByteBuffer.duplicate() does not promise to retain byte order. ShaderModule has already
        // established native-order SPIR-V, so retain that verified order before reading words.
        ByteBuffer code = source.duplicate().order(source.order());
        if (code.remaining() < HEADER_BYTES || (code.remaining() & 3) != 0) {
            throw new VulkanGenericPipelineCompilationException("SPIR-V descriptor reflection received malformed bytecode");
        }
        int start = code.position();
        int end = code.limit();
        Map<Integer, Type> types = new HashMap<>();
        Map<Integer, Long> constants = new HashMap<>();
        Map<Integer, Variable> variables = new LinkedHashMap<>();
        Map<Integer, Integer> descriptorSets = new HashMap<>();
        Map<Integer, Integer> bindings = new HashMap<>();
        Set<Integer> nonWritable = new HashSet<>();
        Set<EntryPoint> entryPoints = new HashSet<>();
        for (int offset = start + HEADER_BYTES; offset < end;) {
            int instruction = code.getInt(offset);
            int wordCount = instruction >>> 16;
            int opcode = instruction & 0xffff;
            if (wordCount == 0 || offset + wordCount * Integer.BYTES > end) {
                throw new VulkanGenericPipelineCompilationException("SPIR-V contains a truncated descriptor instruction");
            }
            switch (opcode) {
                case OP_ENTRY_POINT -> {
                    requireWords(wordCount, 4, "OpEntryPoint");
                    entryPoints.add(new EntryPoint(string(code, offset, wordCount, 3), word(code, offset, 1)));
                }
                case OP_DECORATE -> {
                    requireWords(wordCount, 3, "OpDecorate");
                    int target = word(code, offset, 1);
                    int decoration = word(code, offset, 2);
                    if (decoration == DECORATION_NON_WRITABLE) nonWritable.add(target);
                    if (decoration == DECORATION_BINDING || decoration == DECORATION_DESCRIPTOR_SET) {
                        requireWords(wordCount, 4, "descriptor OpDecorate");
                        Map<Integer, Integer> destination = decoration == DECORATION_BINDING ? bindings : descriptorSets;
                        if (destination.putIfAbsent(target, word(code, offset, 3)) != null) {
                            throw new VulkanGenericPipelineCompilationException("SPIR-V decorates one descriptor location twice");
                        }
                    }
                }
                case OP_TYPE_INT -> {
                    requireWords(wordCount, 4, "OpTypeInt");
                    types.put(word(code, offset, 1), new ScalarType());
                }
                case OP_TYPE_IMAGE -> {
                    requireWords(wordCount, 9, "OpTypeImage");
                    types.put(word(code, offset, 1), new ImageType(word(code, offset, 7)));
                }
                case OP_TYPE_SAMPLER -> {
                    requireWords(wordCount, 2, "OpTypeSampler");
                    types.put(word(code, offset, 1), new SamplerType());
                }
                case OP_TYPE_SAMPLED_IMAGE -> {
                    requireWords(wordCount, 3, "OpTypeSampledImage");
                    types.put(word(code, offset, 1), new SampledImageType());
                }
                case OP_TYPE_ARRAY -> {
                    requireWords(wordCount, 4, "OpTypeArray");
                    types.put(word(code, offset, 1), new ArrayType(word(code, offset, 2), word(code, offset, 3)));
                }
                case OP_TYPE_RUNTIME_ARRAY -> {
                    requireWords(wordCount, 3, "OpTypeRuntimeArray");
                    types.put(word(code, offset, 1), new RuntimeArrayType());
                }
                case OP_TYPE_STRUCT -> {
                    requireWords(wordCount, 2, "OpTypeStruct");
                    types.put(word(code, offset, 1), new StructType());
                }
                case OP_TYPE_POINTER -> {
                    requireWords(wordCount, 4, "OpTypePointer");
                    types.put(word(code, offset, 1), new PointerType(word(code, offset, 2), word(code, offset, 3)));
                }
                case OP_CONSTANT -> {
                    requireWords(wordCount, 4, "OpConstant");
                    constants.put(word(code, offset, 2), Integer.toUnsignedLong(word(code, offset, 3)));
                }
                case OP_VARIABLE -> {
                    requireWords(wordCount, 4, "OpVariable");
                    variables.put(word(code, offset, 2), new Variable(word(code, offset, 1), word(code, offset, 3)));
                }
                default -> {
                    // Non-interface instructions are intentionally irrelevant to descriptor shape.
                }
            }
            offset += wordCount * Integer.BYTES;
        }
        return new ParsedModule(types, constants, variables, descriptorSets, bindings, nonWritable, entryPoints);
    }

    private static ReflectedBinding resolve(
            Variable variable, int variableId, ParsedModule parsed, ShaderModule module
    ) {
        Type pointer = parsed.types().get(variable.pointerType());
        if (!(pointer instanceof PointerType typedPointer)) {
            throw unsupported("descriptor variable has no pointer type", module);
        }
        if (typedPointer.storageClass() != variable.storageClass()) {
            throw unsupported("descriptor pointer and variable storage classes disagree", module);
        }
        long arrayCount = 1L;
        int typeId = typedPointer.pointeeType();
        while (true) {
            Type type = parsed.types().get(typeId);
            if (type instanceof ArrayType array) {
                Long length = parsed.constants().get(array.lengthId());
                if (length == null || length <= 0L) {
                    throw unsupported("descriptor array has no positive constant length", module);
                }
                try {
                    arrayCount = Math.multiplyExact(arrayCount, length);
                } catch (ArithmeticException overflow) {
                    throw unsupported("descriptor array count overflows", module, overflow);
                }
                typeId = array.elementType();
                continue;
            }
            if (type instanceof RuntimeArrayType) {
                throw unsupported("runtime descriptor arrays are not expressible by a fixed binding layout", module);
            }
            if (arrayCount > Integer.MAX_VALUE) {
                throw unsupported("descriptor array count exceeds the public binding limit", module);
            }
            return new ReflectedBinding(
                    descriptorType(variable.storageClass(), type, parsed.nonWritable().contains(variableId), module),
                    (int) arrayCount
            );
        }
    }

    private static BindingType descriptorType(
            int storageClass, Type type, boolean nonWritable, ShaderModule module
    ) {
        if (storageClass == STORAGE_UNIFORM_CONSTANT) {
            if (type instanceof SampledImageType) return BindingType.COMBINED_IMAGE_SAMPLER;
            if (type instanceof SamplerType) return BindingType.SAMPLER;
            if (type instanceof ImageType image) {
                return switch (image.sampled()) {
                    case IMAGE_SAMPLED -> BindingType.SAMPLED_TEXTURE;
                    case IMAGE_STORAGE -> nonWritable
                            ? BindingType.READ_ONLY_STORAGE_TEXTURE : BindingType.READ_WRITE_STORAGE_TEXTURE;
                    default -> throw unsupported("image descriptor has an unsupported sampled/storage mode", module);
                };
            }
        }
        if (storageClass == STORAGE_UNIFORM && type instanceof StructType) return BindingType.UNIFORM_BUFFER;
        if (storageClass == STORAGE_STORAGE_BUFFER && type instanceof StructType) {
            return nonWritable ? BindingType.READ_ONLY_STORAGE_BUFFER : BindingType.READ_WRITE_STORAGE_BUFFER;
        }
        throw unsupported("descriptor uses a storage class/type not expressible by the public binding API", module);
    }

    private static boolean compatible(BindingType declared, BindingType reflected) {
        if (declared == reflected) return true;
        // SPIR-V descriptor declarations do not encode whether sampling instructions are depth
        // comparisons. Both public sampler variants intentionally map to the same Vulkan type.
        return reflected == BindingType.SAMPLER
                && (declared == BindingType.SAMPLER || declared == BindingType.COMPARISON_SAMPLER);
    }

    private static int executionModel(ShaderStage stage) {
        return switch (stage) {
            case VERTEX -> 0;
            case TESSELLATION_CONTROL -> 1;
            case TESSELLATION_EVALUATION -> 2;
            case GEOMETRY -> 3;
            case FRAGMENT -> 4;
            case COMPUTE -> 5;
            case RAY_GENERATION -> 5313;
            case RAY_INTERSECTION -> 5314;
            case RAY_ANY_HIT -> 5315;
            case RAY_CLOSEST_HIT -> 5316;
            case RAY_MISS -> 5317;
            case CALLABLE -> 5318;
        };
    }

    private static int word(ByteBuffer code, int offset, int operand) {
        return code.getInt(offset + operand * Integer.BYTES);
    }

    private static String string(ByteBuffer code, int offset, int wordCount, int firstOperand) {
        byte[] bytes = new byte[(wordCount - firstOperand) * Integer.BYTES];
        int length = 0;
        for (int operand = firstOperand; operand < wordCount; operand++) {
            int value = word(code, offset, operand);
            for (int byteIndex = 0; byteIndex < Integer.BYTES; byteIndex++) {
                byte next = (byte) (value >>> (byteIndex * Byte.SIZE));
                if (next == 0) return new String(bytes, 0, length, StandardCharsets.UTF_8);
                bytes[length++] = next;
            }
        }
        throw new VulkanGenericPipelineCompilationException("SPIR-V entry point name is not null terminated");
    }

    private static void requireWords(int actual, int minimum, String instruction) {
        if (actual < minimum) {
            throw new VulkanGenericPipelineCompilationException(instruction + " has too few operands");
        }
    }

    private static VulkanGenericPipelineCompilationException unsupported(String detail, ShaderModule module) {
        return new VulkanGenericPipelineCompilationException(
                "SPIR-V descriptor interface is not representable for module " + module.id() + ": " + detail
        );
    }

    private static VulkanGenericPipelineCompilationException unsupported(
            String detail, ShaderModule module, Throwable cause
    ) {
        return new VulkanGenericPipelineCompilationException(
                "SPIR-V descriptor interface is not representable for module " + module.id() + ": " + detail,
                cause
        );
    }

    private sealed interface Type permits ScalarType, ImageType, SamplerType, SampledImageType,
            ArrayType, RuntimeArrayType, StructType, PointerType {
    }

    private record ScalarType() implements Type {
    }

    private record ImageType(int sampled) implements Type {
    }

    private record SamplerType() implements Type {
    }

    private record SampledImageType() implements Type {
    }

    private record ArrayType(int elementType, int lengthId) implements Type {
    }

    private record RuntimeArrayType() implements Type {
    }

    private record StructType() implements Type {
    }

    private record PointerType(int storageClass, int pointeeType) implements Type {
    }

    private record Variable(int pointerType, int storageClass) {
    }

    private record ReflectedBinding(BindingType type, int arrayCount) {
    }

    private record EntryPoint(String name, int executionModel) {
    }

    private record ParsedModule(
            Map<Integer, Type> types,
            Map<Integer, Long> constants,
            Map<Integer, Variable> variables,
            Map<Integer, Integer> descriptorSets,
            Map<Integer, Integer> bindings,
            Set<Integer> nonWritable,
            Set<EntryPoint> entryPoints
    ) {
    }
}
