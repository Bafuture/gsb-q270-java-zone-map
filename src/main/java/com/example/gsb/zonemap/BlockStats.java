package com.example.gsb.zonemap;

/**
 * 单个数据块（zone）的统计信息：非空值的最小值 / 最大值，以及空值计数。
 *
 * <p>min/max 只统计非空值。一个没有任何非空值（空块或全空块）时，
 * {@code hasValues()} 返回 {@code false}，此时任意带非空边界的等值/范围
 * 谓词都不可能命中该块，可被安全跳过。</p>
 */
public final class BlockStats {

    private final int startRowIndex;
    private final int capacity;
    private int size;
    private long min;
    private long max;
    private int nullCount;

    BlockStats(int startRowIndex, int capacity) {
        this.startRowIndex = startRowIndex;
        this.capacity = capacity;
        this.size = 0;
        this.min = 0L;
        this.max = 0L;
        this.nullCount = 0;
    }

    /** 块内首个行下标（含）。 */
    public int startRowIndex() {
        return startRowIndex;
    }

    /** 块内实际行数（含空值）。 */
    public int size() {
        return size;
    }

    /** 块容量上限（最后一块可能不满）。 */
    public int capacity() {
        return capacity;
    }

    /** 块内是否存在至少一个非空值。 */
    public boolean hasValues() {
        return size > nullCount;
    }

    /** 非空值最小值；块内无非空值时无定义，调用前需检查 {@link #hasValues()}。 */
    public long min() {
        if (!hasValues()) {
            throw new IllegalStateException("block has no non-null value");
        }
        return min;
    }

    /** 非空值最大值；块内无非空值时无定义，调用前需检查 {@link #hasValues()}。 */
    public long max() {
        if (!hasValues()) {
            throw new IllegalStateException("block has no non-null value");
        }
        return max;
    }

    public int nullCount() {
        return nullCount;
    }

    void setSize(int size) {
        this.size = size;
    }

    void setMinMax(long min, long max) {
        this.min = min;
        this.max = max;
    }

    void setNullCount(int nullCount) {
        this.nullCount = nullCount;
    }

    @Override
    public String toString() {
        if (!hasValues()) {
            return "BlockStats[rows=" + size + ", all-null, nullCount=" + nullCount + "]";
        }
        return "BlockStats[rows=" + size + ", min=" + min + ", max=" + max
                + ", nullCount=" + nullCount + "]";
    }
}
