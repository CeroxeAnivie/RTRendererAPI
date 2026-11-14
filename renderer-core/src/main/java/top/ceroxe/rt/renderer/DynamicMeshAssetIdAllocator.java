package top.ceroxe.rt.renderer;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocates collision-free IDs across renderer-owned dynamic geometry domains.
 */
public final class DynamicMeshAssetIdAllocator {
    private static final int DOMAIN_SHIFT = 56;
    private static final long SEQUENCE_MASK = (1L << DOMAIN_SHIFT) - 1L;
    private static final EnumMap<Domain, AtomicLong> NEXT_SEQUENCES = counters();

    private DynamicMeshAssetIdAllocator() {
    }

    /**
     * 为指定动态几何域分配下一个进程内唯一标识。
     *
     * @param domain 资产所属的动态几何域
     * @return 带域标签的正资产标识
     */
    public static long next(Domain domain) {
        if (domain == null) {
            throw new NullPointerException("domain");
        }
        AtomicLong counter = NEXT_SEQUENCES.get(domain);
        while (true) {
            long sequence = counter.get();
            if (sequence <= 0L || sequence > SEQUENCE_MASK) {
                throw new IllegalStateException("dynamic mesh asset id space exhausted for domain " + domain);
            }
            if (counter.compareAndSet(sequence, sequence + 1L)) {
                return ((long) domain.tag() << DOMAIN_SHIFT) | sequence;
            }
        }
    }

    /**
     * 解析资产标识编码的动态几何域。
     *
     * @param assetId 带域标签的正资产标识
     * @return 编码的动态几何域
     */
    public static Domain domain(long assetId) {
        if (assetId <= 0L) {
            throw new IllegalArgumentException("dynamic mesh asset id must be positive");
        }
        int tag = (int) (assetId >>> DOMAIN_SHIFT);
        for (Domain domain : Domain.values()) {
            if (domain.tag() == tag) {
                return domain;
            }
        }
        throw new IllegalArgumentException("unknown dynamic mesh asset id domain: " + tag);
    }

    private static EnumMap<Domain, AtomicLong> counters() {
        EnumMap<Domain, AtomicLong> counters = new EnumMap<>(Domain.class);
        for (Domain domain : Domain.values()) {
            counters.put(domain, new AtomicLong(1L));
        }
        return counters;
    }

    /**
     * 相互隔离的动态几何资产标识空间。
     */
    public enum Domain {
        /**
         * 物品或小型可实例化网格。
         */
        ITEM(1),
        /**
         * 规则模型立方体网格。
         */
        MODEL_CUBE(2),
        /**
         * 程序生成的任意动态模型网格。
         */
        PROCEDURAL_MODEL(3);

        private final int tag;

        Domain(int tag) {
            if (tag <= 0 || tag >= 0x80) {
                throw new IllegalArgumentException("dynamic mesh asset domain tag must be in [1, 127]");
            }
            this.tag = tag;
        }

        int tag() {
            return tag;
        }
    }
}
