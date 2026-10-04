package com.example.gsb.zonemap;

/**
 * 单个数据块的统计信息（zone map 条目）：最小值、最大值与空值计数。
 *
 * <p>统计信息始终保持精确：任何 {@code insert} / {@code update} 都会同步维护本结构
 * （扩大区间时增量更新，可能缩小区间时整块重算），因此跳过判断永远不会因为
 * 过期统计而漏掉本应命中的数据。
 */
public final class BlockStats {

    private long min;
    private long max;
    private long nonNullCount;
    private long nullCount;

    BlockStats() {
    }

    private BlockStats(long min, long max, long nonNullCount, long nullCount) {
        this.min = min;
        this.max = max;
        this.nonNullCount = nonNullCount;
        this.nullCount = nullCount;
    }

    /** 块内最小值；仅当 {@link #nonNullCount()} &gt; 0 时有意义。 */
    public long min() {
        return min;
    }

    /** 块内最大值；仅当 {@link #nonNullCount()} &gt; 0 时有意义。 */
    public long max() {
        return max;
    }

    /** 块内非空值条数。 */
    public long nonNullCount() {
        return nonNullCount;
    }

    /** 块内空值（null）条数。 */
    public long nullCount() {
        return nullCount;
    }

    /** 块内总条数。 */
    public long rowCount() {
        return nonNullCount + nullCount;
    }

    void add(Long value) {
        if (value == null) {
            nullCount++;
            return;
        }
        if (nonNullCount == 0) {
            min = value;
            max = value;
        } else {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        nonNullCount++;
    }

    /**
     * 移除一个已知不是当前 min/max 边界的值（边界情况由调用方触发整块重算），
     * 因此这里只需维护计数，min/max 不变。
     */
    void removeNonBoundary(Long value) {
        if (value == null) {
            nullCount--;
        } else {
            nonNullCount--;
        }
    }

    BlockStats copy() {
        return new BlockStats(min, max, nonNullCount, nullCount);
    }

    @Override
    public String toString() {
        return "BlockStats{min=" + min + ", max=" + max
                + ", nonNullCount=" + nonNullCount + ", nullCount=" + nullCount + '}';
    }
}
