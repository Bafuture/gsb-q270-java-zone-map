package com.example.gsb.zonemap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongPredicate;

/**
 * 分块跳过索引（zone map）。
 *
 * <p>数据按定长块（block size 可配置）物理存储，每块维护 {@link BlockStats}：
 * 最小值、最大值与空值计数。查询时先用块级统计信息判断谓词区间与块的值域
 * {@code [min, max]} 是否可能相交，不可能相交的块整块跳过；可能相交的块仍需
 * 逐行做块内二次过滤——统计信息只用于“排除”，绝不用于“确认命中”，因此结果
 * 与全表扫描完全一致。
 *
 * <h2>更新后统计信息的维护（保证不失效）</h2>
 * <ol>
 *   <li><b>插入</b>：增量维护，新值只会让值域扩大（或不变），直接更新
 *       min/max 与空值计数。</li>
 *   <li><b>更新时值域扩大</b>：新值在 {@code [min,max]} 之外，增量更新
 *       min/max 即可，统计仍精确。</li>
 *   <li><b>更新时值域可能缩小</b>：当旧值恰好等于当前 min 或 max 时，删除它
 *       可能导致 min/max 上移/下移，无法在 O(1) 内确定新边界。此时对该块
 *       执行一次整块重算（O(blockSize)），从块内现存数据重建统计信息。</li>
 * </ol>
 * 即“扩大走增量、缩小走重算”，任何更新之后 min/max/nullCount 都与块内真实
 * 数据严格一致，跳过判断不会因过期统计而漏数据。
 *
 * <h2>块大小（block size）的权衡</h2>
 * 块越小，块级 {@code [min,max]} 通常越窄，跳过率越高，但块数（元数据量）
 * 越多、每个未跳过块都有固定开销；块越大，元数据越少、单次顺序扫描更高效，
 * 但值域更容易与查询区间相交，跳过率下降、块内二次过滤扫描的行数增多。
 * 应按数据聚簇程度与查询选择率选择：聚簇好、选择率低的数据适合小块。
 */
public final class ZoneMapTable {

    /** 默认块大小（行数）。 */
    public static final int DEFAULT_BLOCK_SIZE = 1024;

    private final int blockSize;
    private final List<Long> values = new ArrayList<>();
    private final List<BlockStats> blockStats = new ArrayList<>();
    private final ScanStats cumulativeStats = new ScanStats();

    public ZoneMapTable() {
        this(DEFAULT_BLOCK_SIZE);
    }

    /**
     * @param blockSize 每个块最多容纳的行数，必须为正
     */
    public ZoneMapTable(int blockSize) {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive, got " + blockSize);
        }
        this.blockSize = blockSize;
    }

    /** 块大小（每块最多行数）。 */
    public int blockSize() {
        return blockSize;
    }

    /** 总行数（含空值）。 */
    public int size() {
        return values.size();
    }

    /** 当前总块数。 */
    public int blockCount() {
        return blockStats.size();
    }

    /** 读取指定行号的当前值（可能为 null）。 */
    public Long valueAt(long rowId) {
        return values.get(checkRowId(rowId));
    }

    /**
     * 追加一行（可为 null），返回其行号。
     */
    public long insert(Long value) {
        int rowId = values.size();
        values.add(value);
        int blockIndex = rowId / blockSize;
        if (blockIndex == blockStats.size()) {
            blockStats.add(new BlockStats());
        }
        blockStats.get(blockIndex).add(value);
        return rowId;
    }

    /**
     * 更新指定行号的值（可为 null）。
     *
     * <p>若旧值是所在块当前的 min/max 边界，则整块重算统计信息，保证
     * min/max 不会因删除边界值而残留一个已经失效的过宽/过窄边界。
     */
    public void update(long rowId, Long newValue) {
        int index = checkRowId(rowId);
        Long oldValue = values.get(index);
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        values.set(index, newValue);

        int blockIndex = index / blockSize;
        BlockStats stats = blockStats.get(blockIndex);

        // 旧值是边界：新值可能让值域缩小（也可能扩大），O(1) 无法确定新边界，
        // 直接从块内现存数据重算，统计信息必定精确。
        boolean oldValueWasBoundary = oldValue != null
                && stats.nonNullCount() > 0
                && (oldValue == stats.min() || oldValue == stats.max());
        if (oldValueWasBoundary) {
            recomputeBlock(blockIndex);
            return;
        }

        // 旧值不是边界：删除它不会改变 min/max；新值只会让值域扩大或不变。
        stats.removeNonBoundary(oldValue);
        stats.add(newValue);
    }

    /** 等值查询：返回所有值等于 {@code value} 的行。 */
    public ScanResult queryEquals(long value) {
        return scan(new ZonePredicate() {
            @Override
            public boolean mayContain(BlockStats s) {
                // 全空块或值不在 [min,max] 内 -> 整块跳过。
                return s.nonNullCount() > 0 && value >= s.min() && value <= s.max();
            }

            @Override
            public boolean matches(long v) {
                return v == value;
            }
        });
    }

    /** 双闭区间范围查询：{@code lower <= v <= upper}。 */
    public ScanResult queryRange(long lower, long upper) {
        return queryRange(lower, true, upper, true);
    }

    /**
     * 范围查询，边界可分别指定是否包含；{@code null} 边界表示该侧无界。
     *
     * @param lower 下界（null 表示无下界）
     * @param lowerInclusive 是否包含下界
     * @param upper 上界（null 表示无上界）
     * @param upperInclusive 是否包含上界
     */
    public ScanResult queryRange(Long lower, boolean lowerInclusive,
                                 Long upper, boolean upperInclusive) {
        validateRange(lower, lowerInclusive, upper, upperInclusive);
        return scan(new ZonePredicate() {
            @Override
            public boolean mayContain(BlockStats s) {
                if (s.nonNullCount() == 0) {
                    return false;
                }
                if (lower != null) {
                    boolean maxBeforeLower = lowerInclusive ? s.max() < lower : s.max() <= lower;
                    if (maxBeforeLower) {
                        return false;
                    }
                }
                if (upper != null) {
                    boolean minAfterUpper = upperInclusive ? s.min() > upper : s.min() >= upper;
                    if (minAfterUpper) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean matches(long v) {
                if (lower != null) {
                    if (lowerInclusive ? v < lower : v <= lower) {
                        return false;
                    }
                }
                if (upper != null) {
                    return upperInclusive ? v <= upper : v < upper;
                }
                return true;
            }
        });
    }

    /**
     * 不使用任何统计信息的全表扫描基线：逐行对非空值应用谓词。
     * 用于正确性对比——zone map 查询结果必须与其完全一致。
     */
    public List<Long> fullScan(LongPredicate predicate) {
        Objects.requireNonNull(predicate, "predicate");
        List<Long> matched = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Long v = values.get(i);
            if (v != null && predicate.test(v)) {
                matched.add((long) i);
            }
        }
        return matched;
    }

    /** 各块统计信息的只读快照（拷贝，不随表变化）。 */
    public List<BlockStats> blockStats() {
        List<BlockStats> copies = new ArrayList<>(blockStats.size());
        for (BlockStats s : blockStats) {
            copies.add(s.copy());
        }
        return copies;
    }

    /** 自表创建以来所有查询的累计统计快照。 */
    public ScanStats cumulativeStats() {
        return cumulativeStats.snapshot();
    }

    private int checkRowId(long rowId) {
        if (rowId < 0 || rowId >= values.size()) {
            throw new IndexOutOfBoundsException(
                    "rowId " + rowId + " out of bounds for size " + values.size());
        }
        return (int) rowId;
    }

    private void recomputeBlock(int blockIndex) {
        BlockStats fresh = new BlockStats();
        int from = blockIndex * blockSize;
        int to = Math.min(from + blockSize, values.size());
        for (int i = from; i < to; i++) {
            fresh.add(values.get(i));
        }
        blockStats.set(blockIndex, fresh);
    }

    private static void validateRange(Long lower, boolean lowerInclusive,
                                      Long upper, boolean upperInclusive) {
        if (lower != null && upper != null) {
            if (lower > upper) {
                throw new IllegalArgumentException("lower bound " + lower
                        + " must not be greater than upper bound " + upper);
            }
            if (lower.equals(upper) && (!lowerInclusive || !upperInclusive)) {
                throw new IllegalArgumentException(
                        "range with equal bounds must be inclusive on both sides: [" + lower + "]");
            }
        }
    }

    /**
     * 查询谓词：{@link #mayContain(BlockStats)} 是块级“可能相交”判断（粗过滤），
     * {@link #matches(long)} 是块内逐行精确判断（二次过滤）。
     */
    private interface ZonePredicate {
        boolean mayContain(BlockStats stats);

        boolean matches(long value);
    }

    private ScanResult scan(ZonePredicate predicate) {
        ScanStats stats = new ScanStats();
        stats.totalBlocks(blockStats.size());

        List<Long> rowIds = new ArrayList<>();
        List<Long> matchedValues = new ArrayList<>();

        for (int blockIndex = 0; blockIndex < blockStats.size(); blockIndex++) {
            BlockStats block = blockStats.get(blockIndex);
            if (!predicate.mayContain(block)) {
                stats.skipBlock();
                continue;
            }
            int from = blockIndex * blockSize;
            int to = Math.min(from + blockSize, values.size());
            for (int i = from; i < to; i++) {
                stats.scanRow();
                Long v = values.get(i);
                // 统计信息只保证“可能相交”，必须逐行二次过滤；空值不匹配任何谓词。
                if (v != null && predicate.matches(v)) {
                    stats.matchRow();
                    rowIds.add((long) i);
                    matchedValues.add(v);
                }
            }
        }

        cumulativeStats.merge(stats);
        return new ScanResult(rowIds, matchedValues, stats);
    }
}
