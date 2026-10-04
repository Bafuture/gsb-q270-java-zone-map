package com.example.gsb.zonemap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * 分块跳过索引（zone map）。
 *
 * <p>行按写入顺序划分为固定大小的块，每块维护 {@link BlockStats}
 * （非空值 min/max 与空值计数）。查询时先用块的 min/max 做区间相交判断，
 * 不相交的块整体跳过；可能相交的块进入块内二次过滤，逐条判定，
 * 因此统计信息只用于“剪枝”，绝不直接决定结果，保证与全表扫描一致。</p>
 *
 * <h3>更新与统计信息维护</h3>
 * <p>{@link #set(int, Long)} 更新一行后按如下策略维护所在块的统计信息，
 * 保证 min/max 永不失效（永不漏命中）：</p>
 * <ul>
 *   <li>被替换的旧值不是当前 min/max：边界只可能不变或扩张，
 *       按需扩张即可，O(1)；</li>
 *   <li>被替换的旧值是当前 min 或 max：边界可能收缩，而收缩无法增量
 *       维护（不扫描就不知道次小/次大值），此时整块重算统计信息，
 *       O(blockSize)，保证 min/max 永远精确、永不失效。</li>
 * </ul>
 * <p>空值计数在上述所有分支中均可 O(1) 增量维护。</p>
 */
public final class ZoneMapTable {

    private final int blockSize;
    private final List<Long> rows = new ArrayList<>();
    private final List<BlockStats> blocks = new ArrayList<>();

    /**
     * @param blockSize 每块行数，必须为正数。块越小 min/max 越紧、跳过率越高，
     *                  但块数变多、元数据与跳过判断开销变大；块越大则相反，
     *                  且未跳过块内的二次过滤扫描量变大。
     */
    public ZoneMapTable(int blockSize) {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        this.blockSize = blockSize;
    }

    public int blockSize() {
        return blockSize;
    }

    /** 总行数。 */
    public int size() {
        return rows.size();
    }

    /** 总块数。 */
    public int blockCount() {
        return blocks.size();
    }

    /** 读取一行（可能为 {@code null}）。 */
    public Long get(int rowIndex) {
        return rows.get(rowIndex);
    }

    /** 只读视图：各块统计信息。 */
    public List<BlockStats> blockStats() {
        return Collections.unmodifiableList(blocks);
    }

    /** 追加一行，自动维护所在块（必要时新建块）的统计信息。 */
    public void add(Long value) {
        int rowIndex = rows.size();
        rows.add(value);
        if (rowIndex % blockSize == 0) {
            blocks.add(new BlockStats(rowIndex, blockSize));
        }
        BlockStats stats = blocks.get(blocks.size() - 1);
        boolean firstValue = !stats.hasValues();
        stats.setSize(stats.size() + 1);
        if (value == null) {
            stats.setNullCount(stats.nullCount() + 1);
        } else if (firstValue) {
            stats.setMinMax(value, value);
        } else {
            stats.setMinMax(Math.min(stats.min(), value), Math.max(stats.max(), value));
        }
    }

    /**
     * 更新一行并维护所在块的统计信息（见类注释的维护策略）。
     */
    public void set(int rowIndex, Long value) {
        Long oldValue = rows.set(rowIndex, value);
        BlockStats stats = blocks.get(rowIndex / blockSize);

        boolean hadValues = stats.hasValues();
        boolean boundaryRemoved = oldValue != null && hadValues
                && (oldValue == stats.min() || oldValue == stats.max());

        int nullDelta = (value == null ? 1 : 0) - (oldValue == null ? 1 : 0);
        stats.setNullCount(stats.nullCount() + nullDelta);

        if (boundaryRemoved) {
            recomputeBlockStats(rowIndex / blockSize);
        } else if (value != null) {
            if (!hadValues) {
                stats.setMinMax(value, value);
            } else {
                stats.setMinMax(Math.min(stats.min(), value), Math.max(stats.max(), value));
            }
        }
    }

    /** 整块重算统计信息，用于边界收缩等无法增量维护的场景。 */
    private void recomputeBlockStats(int blockIndex) {
        BlockStats stats = blocks.get(blockIndex);
        int start = stats.startRowIndex();
        int end = Math.min(start + blockSize, rows.size());
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        int nullCount = 0;
        for (int i = start; i < end; i++) {
            Long v = rows.get(i);
            if (v == null) {
                nullCount++;
            } else {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        stats.setNullCount(nullCount);
        if (nullCount < end - start) {
            stats.setMinMax(min, max);
        }
    }

    /** 等值查询：返回所有等于 {@code value} 的行下标。 */
    public QueryResult queryEquals(long value) {
        return query(stats -> stats.hasValues() && value >= stats.min() && value <= stats.max(),
                v -> v == value);
    }

    /** 范围查询（闭区间）：返回所有满足 {@code lower <= v <= upper} 的行下标。 */
    public QueryResult queryRange(long lower, long upper) {
        if (lower > upper) {
            throw new IllegalArgumentException("lower > upper: " + lower + " > " + upper);
        }
        return query(stats -> stats.hasValues() && stats.min() <= upper && stats.max() >= lower,
                v -> v >= lower && v <= upper);
    }

    /**
     * 两阶段查询骨架：
     * <ol>
     *   <li>块级剪枝：谓词区间与块 [min, max] 不相交则整块跳过；</li>
     *   <li>块内二次过滤：对未跳过的块逐条判定，只返回真正命中的行。</li>
     * </ol>
     */
    private QueryResult query(java.util.function.Predicate<BlockStats> blockMayMatch,
                              LongPredicate rowMatches) {
        List<Integer> matched = new ArrayList<>();
        int skippedBlocks = 0;
        long scannedRows = 0;
        for (BlockStats stats : blocks) {
            if (!blockMayMatch.test(stats)) {
                skippedBlocks++;
                continue;
            }
            int start = stats.startRowIndex();
            int end = Math.min(start + blockSize, rows.size());
            for (int i = start; i < end; i++) {
                scannedRows++;
                Long v = rows.get(i);
                if (v != null && rowMatches.test(v)) {
                    matched.add(i);
                }
            }
        }
        return new QueryResult(matched,
                new QueryStats(blocks.size(), skippedBlocks, scannedRows, matched.size()));
    }

    /** 全表扫描基线（不读任何统计信息），用于正确性对照。 */
    public List<Integer> fullScanEquals(long value) {
        return fullScan(v -> v == value);
    }

    /** 全表扫描基线（不读任何统计信息），用于正确性对照。 */
    public List<Integer> fullScanRange(long lower, long upper) {
        return fullScan(v -> v >= lower && v <= upper);
    }

    private List<Integer> fullScan(LongPredicate rowMatches) {
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Long v = rows.get(i);
            if (v != null && rowMatches.test(v)) {
                matched.add(i);
            }
        }
        return matched;
    }
}
