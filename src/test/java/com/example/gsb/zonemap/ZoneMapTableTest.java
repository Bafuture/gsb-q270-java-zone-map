package com.example.gsb.zonemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ZoneMapTableTest {

    /** 构造聚簇数据：10 个块，每块 100 行，全局值域连续，块 b 覆盖 [b*100, b*100+99]。 */
    private static ZoneMapTable clusteredTable() {
        ZoneMapTable table = new ZoneMapTable(100);
        for (int block = 0; block < 10; block++) {
            for (int j = 0; j < 100; j++) {
                table.insert((long) block * 100 + j);
            }
        }
        return table;
    }

    // ---- 要求 2：等值查询按 min/max 跳过，给出跳过块数/总块数对比 ----

    @Test
    void equalityQuerySkipsNonOverlappingBlocks() {
        ZoneMapTable table = clusteredTable();

        ScanResult result = table.queryEquals(555);

        assertThat(result.values()).containsExactly(555L);
        assertThat(result.stats().totalBlocks()).isEqualTo(10);
        assertThat(result.stats().skippedBlocks()).isEqualTo(9);
        assertThat(result.stats().scannedBlocks()).isEqualTo(1);
        assertThat(result.stats().scannedRows()).isEqualTo(100);
        assertThat(result.stats().matchedRows()).isEqualTo(1);
        assertThat(result.stats().skipRatio()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void equalityMissSkipsEveryBlockAndScansNothing() {
        ZoneMapTable table = clusteredTable();

        ScanResult result = table.queryEquals(9_999_999);

        assertThat(result.values()).isEmpty();
        assertThat(result.rowIds()).isEmpty();
        assertThat(result.stats().skippedBlocks()).isEqualTo(10);
        assertThat(result.stats().scannedBlocks()).isZero();
        assertThat(result.stats().scannedRows()).isZero();
        assertThat(result.stats().matchedRows()).isZero();
    }

    // ---- 要求 2：范围查询按 min/max 跳过（含开区间） ----

    @Test
    void rangeQueryScansOnlyOverlappingBlocks() {
        ZoneMapTable table = clusteredTable();

        ScanResult result = table.queryRange(150, 250);

        // 只有块 1 [100,199] 与块 2 [200,299] 与区间相交
        assertThat(result.stats().totalBlocks()).isEqualTo(10);
        assertThat(result.stats().skippedBlocks()).isEqualTo(8);
        assertThat(result.stats().scannedBlocks()).isEqualTo(2);
        assertThat(result.stats().scannedRows()).isEqualTo(200);
        // 150..250 共 101 个连续值
        assertThat(result.stats().matchedRows()).isEqualTo(101);
        assertThat(result.rowIds())
                .isEqualTo(table.fullScan(v -> v >= 150 && v <= 250));
    }

    @Test
    void exclusiveBoundsAreReflectedInBothSkipAndFilter() {
        // 两块：[10,20] 与 [30,40]
        ZoneMapTable table = new ZoneMapTable(2);
        table.insert(10L);
        table.insert(20L);
        table.insert(30L);
        table.insert(40L);

        // (20, 30) 开区间：块0 max==20 被排除，块1 min==30 被排除
        ScanResult open = table.queryRange(20L, false, 30L, false);
        assertThat(open.values()).isEmpty();
        assertThat(open.stats().skippedBlocks()).isEqualTo(2);
        assertThat(open.stats().scannedRows()).isZero();

        // [20, 30] 闭区间：两块都需扫描，块内二次过滤命中 20、30
        ScanResult closed = table.queryRange(20L, true, 30L, true);
        assertThat(closed.values()).containsExactly(20L, 30L);
        assertThat(closed.stats().skippedBlocks()).isZero();
        assertThat(closed.stats().scannedRows()).isEqualTo(4);
    }

    @Test
    void unboundedRangeBoundsAreSupported() {
        ZoneMapTable table = clusteredTable();

        ScanResult upTo = table.queryRange(null, true, 50L, true);
        assertThat(upTo.stats().skippedBlocks()).isEqualTo(9);
        assertThat(upTo.stats().matchedRows()).isEqualTo(51);
        assertThat(upTo.rowIds()).isEqualTo(table.fullScan(v -> v <= 50));

        ScanResult all = table.queryRange(null, true, null, true);
        assertThat(all.stats().skippedBlocks()).isZero();
        assertThat(all.stats().scannedRows()).isEqualTo(1000);
        assertThat(all.stats().matchedRows()).isEqualTo(1000);
    }

    // ---- 要求 3：块内二次过滤（区间相交不等于有命中） ----

    @Test
    void overlappingBlockWithoutMatchIsScannedAndFiltered() {
        ZoneMapTable table = new ZoneMapTable(8);
        table.insert(1L);
        table.insert(100L);
        for (int i = 0; i < 6; i++) {
            table.insert(null);
        }
        // 唯一一块：min=1, max=100, nullCount=6

        ScanResult range = table.queryRange(40, 60);
        assertThat(range.values()).as("区间 [40,60] 与 [1,100] 相交但块内无此值，不能误报")
                .isEmpty();
        assertThat(range.stats().skippedBlocks()).isZero();
        assertThat(range.stats().scannedRows()).as("相交块必须逐行二次过滤").isEqualTo(8);
        assertThat(range.stats().matchedRows()).isZero();

        ScanResult equality = table.queryEquals(50);
        assertThat(equality.values()).isEmpty();
        assertThat(equality.stats().scannedRows()).isEqualTo(8);
        assertThat(equality.stats().matchedRows()).isZero();
    }

    // ---- 要求 1 & 4：空值统计与更新后 min/max/nullCount 维护 ----

    @Test
    void nullsAreCountedAndNeverMatch() {
        ZoneMapTable table = new ZoneMapTable(4);
        table.insert(10L);
        table.insert(null);
        table.insert(20L);
        table.insert(null);

        BlockStats stats = table.blockStats().get(0);
        assertThat(stats.min()).isEqualTo(10);
        assertThat(stats.max()).isEqualTo(20);
        assertThat(stats.nonNullCount()).isEqualTo(2);
        assertThat(stats.nullCount()).isEqualTo(2);

        ScanResult result = table.queryEquals(10);
        assertThat(result.values()).containsExactly(10L);
        assertThat(result.stats().matchedRows()).isEqualTo(1);
    }

    @Test
    void allNullBlockIsAlwaysSkipped() {
        ZoneMapTable table = new ZoneMapTable(4);
        for (int i = 0; i < 4; i++) {
            table.insert(null);
        }

        for (ScanResult result : List.of(table.queryEquals(1), table.queryRange(-5, 5))) {
            assertThat(result.values()).isEmpty();
            assertThat(result.stats().skippedBlocks()).isEqualTo(1);
            assertThat(result.stats().scannedRows()).isZero();
        }
    }

    @Test
    void updateExpandingRangeIsReflectedIncrementally() {
        ZoneMapTable table = new ZoneMapTable(4);
        table.insert(10L);
        table.insert(20L);
        table.insert(30L);
        table.insert(40L);

        table.update(2, 500L); // 30 -> 500：max 扩大

        BlockStats stats = table.blockStats().get(0);
        assertThat(stats.min()).isEqualTo(10);
        assertThat(stats.max()).isEqualTo(500);
        assertThat(stats.nonNullCount()).isEqualTo(4);
        assertThat(stats.nullCount()).isZero();

        assertThat(table.queryEquals(500).values()).containsExactly(500L);

        ScanResult phantom = table.queryEquals(400);
        assertThat(phantom.values()).as("扩大后的区间内不存在的值必须靠二次过滤排除").isEmpty();
        assertThat(phantom.stats().skippedBlocks()).isZero();
        assertThat(phantom.stats().scannedRows()).isEqualTo(4);
        assertThat(phantom.stats().matchedRows()).isZero();
    }

    @Test
    void updateShrinkingRangeTriggersRecompute() {
        ZoneMapTable table = new ZoneMapTable(4);
        table.insert(1L);
        table.insert(50L);
        table.insert(60L);
        table.insert(100L);

        table.update(3, 55L); // 100 -> 55：旧值是 max，max 必须缩小为 60
        assertThat(table.blockStats().get(0).max()).isEqualTo(60);
        assertThat(table.blockStats().get(0).min()).isEqualTo(1);

        // 若 max 残留 100（统计失效），此查询会错误扫描该块；现在应整块跳过
        ScanResult removedMax = table.queryEquals(100);
        assertThat(removedMax.values()).isEmpty();
        assertThat(removedMax.stats().skippedBlocks()).isEqualTo(1);
        assertThat(removedMax.stats().scannedRows()).isZero();

        table.update(0, 45L); // 1 -> 45：旧值是 min，min 应重算为 45
        assertThat(table.blockStats().get(0).min()).isEqualTo(45);
        assertThat(table.blockStats().get(0).max()).isEqualTo(60);

        ScanResult removedMin = table.queryEquals(1);
        assertThat(removedMin.stats().skippedBlocks()).isEqualTo(1);
        assertThat(removedMin.values()).isEmpty();

        // 现存值仍可正常查到
        assertThat(table.queryEquals(60).values()).containsExactly(60L);
    }

    @Test
    void updateToNullAndBackKeepsCountsAndRangeExact() {
        ZoneMapTable table = new ZoneMapTable(4);
        table.insert(10L);
        table.insert(20L);
        table.insert(null);
        table.insert(40L);

        table.update(1, null); // 20 -> null：非边界，只调整计数
        BlockStats afterNull = table.blockStats().get(0);
        assertThat(afterNull.nullCount()).isEqualTo(2);
        assertThat(afterNull.nonNullCount()).isEqualTo(2);
        assertThat(afterNull.min()).isEqualTo(10);
        assertThat(afterNull.max()).isEqualTo(40);

        table.update(3, null); // 40 -> null：旧值是 max，触发重算
        BlockStats afterMaxRemoved = table.blockStats().get(0);
        assertThat(afterMaxRemoved.max()).isEqualTo(10);
        assertThat(afterMaxRemoved.nullCount()).isEqualTo(3);
        assertThat(afterMaxRemoved.nonNullCount()).isEqualTo(1);

        table.update(2, 99L); // null -> 99：空值计数回落，max 扩大
        BlockStats afterFill = table.blockStats().get(0);
        assertThat(afterFill.max()).isEqualTo(99);
        assertThat(afterFill.min()).isEqualTo(10);
        assertThat(afterFill.nullCount()).isEqualTo(2);
        assertThat(afterFill.nonNullCount()).isEqualTo(2);
    }

    @Test
    void updateToSameValueIsNoOp() {
        ZoneMapTable table = new ZoneMapTable(4);
        table.insert(5L);
        table.insert(10L);
        table.insert(15L);
        table.insert(null);

        table.update(1, 10L);
        table.update(3, null);

        BlockStats stats = table.blockStats().get(0);
        assertThat(stats.min()).isEqualTo(5);
        assertThat(stats.max()).isEqualTo(15);
        assertThat(stats.nullCount()).isEqualTo(1);
    }

    // ---- 要求 6：块大小配置及对跳过率/块内扫描量的影响 ----

    @Test
    void smallerBlockSizeTradesMetadataForHigherSkipRate() {
        int rows = 65_536;
        ZoneMapTable small = new ZoneMapTable(64);
        ZoneMapTable large = new ZoneMapTable(4096);
        for (long v = 0; v < rows; v++) {
            small.insert(v);
            large.insert(v);
        }

        // 查询只命中 64 行的极小区间 [10000, 10063]
        ScanResult smallResult = small.queryRange(10_000, 10_063);
        ScanResult largeResult = large.queryRange(10_000, 10_063);

        assertThat(smallResult.values()).isEqualTo(largeResult.values());
        assertThat(smallResult.stats().matchedRows()).isEqualTo(64);
        assertThat(largeResult.stats().matchedRows()).isEqualTo(64);

        // 小块：2 块 * 64 = 128 行；大块：1 块 * 4096 行
        assertThat(smallResult.stats().scannedRows()).isEqualTo(128);
        assertThat(largeResult.stats().scannedRows()).isEqualTo(4096);
        assertThat(smallResult.stats().skipRatio()).isGreaterThan(largeResult.stats().skipRatio());
        assertThat(small.blockCount()).isGreaterThan(large.blockCount());
    }

    // ---- 要求 7：单次与累计统计 ----

    @Test
    void cumulativeStatsAggregateAcrossQueries() {
        ZoneMapTable table = clusteredTable();

        table.queryEquals(555); // 跳过 9，扫描 100，命中 1
        table.queryRange(150, 250); // 跳过 8，扫描 200，命中 101

        ScanStats cumulative = table.cumulativeStats();
        assertThat(cumulative.totalBlocks()).isEqualTo(20);
        assertThat(cumulative.skippedBlocks()).isEqualTo(17);
        assertThat(cumulative.scannedBlocks()).isEqualTo(3);
        assertThat(cumulative.scannedRows()).isEqualTo(300);
        assertThat(cumulative.matchedRows()).isEqualTo(102);
    }

    @Test
    void statsInvariantsHold() {
        ZoneMapTable table = clusteredTable();
        for (ScanResult result : List.of(table.queryEquals(0), table.queryRange(5_000, 5_000))) {
            assertThat(result.stats().skippedBlocks() + result.stats().scannedBlocks())
                    .isEqualTo(result.stats().totalBlocks());
            assertThat(result.stats().scannedRows()).isGreaterThanOrEqualTo(result.stats().matchedRows());
            assertThat(result.stats().matchedRows()).isEqualTo(result.rowIds().size());
            assertThat(result.toString()).contains("skippedBlocks");
        }
    }

    // ---- 边界参数 ----

    @Test
    void rejectsInvalidBlockSizeAndRange() {
        assertThatThrownBy(() -> new ZoneMapTable(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZoneMapTable(-1)).isInstanceOf(IllegalArgumentException.class);

        ZoneMapTable table = clusteredTable();
        assertThatThrownBy(() -> table.queryRange(10, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> table.queryRange(5L, false, 5L, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> table.update(-1, 1L))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> table.update(10_000, 1L))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void emptyTableQueriesAreSafe() {
        ZoneMapTable table = new ZoneMapTable(16);
        ScanResult result = table.queryEquals(1);
        assertThat(result.values()).isEmpty();
        assertThat(result.stats().totalBlocks()).isZero();
        assertThat(result.stats().skipRatio()).isZero();
    }
}
