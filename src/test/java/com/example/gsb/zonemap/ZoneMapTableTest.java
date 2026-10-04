package com.example.gsb.zonemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ZoneMapTableTest {

    private static ZoneMapTable sortedTable(int blockSize, int rows) {
        ZoneMapTable table = new ZoneMapTable(blockSize);
        for (int i = 0; i < rows; i++) {
            table.add((long) i);
        }
        return table;
    }

    @Test
    @DisplayName("等值查询：有序数据下按 min/max 跳过绝大多数块")
    void equalitySkipsBlocksOnSortedData() {
        ZoneMapTable table = sortedTable(16, 1600); // 100 块，块 i 覆盖 [16i, 16i+15]

        QueryResult result = table.queryEquals(800); // 位于第 50 块

        assertThat(result.matchedRowIndexes()).containsExactly(800);
        QueryStats stats = result.stats();
        assertThat(stats.totalBlocks()).isEqualTo(100);
        assertThat(stats.skippedBlocks()).isEqualTo(99);
        assertThat(stats.scannedRows()).isEqualTo(16);
        assertThat(stats.matchedRows()).isEqualTo(1);
        assertThat(stats.skipRatio()).isEqualTo(0.99);
    }

    @Test
    @DisplayName("范围查询：区间外的块被跳过，区间内的块被扫描")
    void rangeSkipsBlocksOutsideInterval() {
        ZoneMapTable table = sortedTable(16, 1600);

        QueryResult result = table.queryRange(100, 199); // 覆盖块 6..12

        assertThat(result.matchedRowIndexes()).hasSize(100);
        assertThat(result.matchedRowIndexes()).contains(100, 199);
        QueryStats stats = result.stats();
        assertThat(stats.totalBlocks()).isEqualTo(100);
        assertThat(stats.skippedBlocks()).isEqualTo(93);
        assertThat(stats.scannedRows()).isEqualTo(7 * 16);
        assertThat(stats.matchedRows()).isEqualTo(100);
    }

    @Test
    @DisplayName("范围查询参数校验：lower > upper 抛异常")
    void rangeRejectsInvertedBounds() {
        ZoneMapTable table = sortedTable(4, 8);
        assertThatThrownBy(() -> table.queryRange(5, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("块内二次过滤：区间相交不代表命中，块内逐条判定不返回错误结果")
    void inBlockSecondFilterPreventsFalsePositives() {
        // 块大小 4，块 0 = [1, 100, null, 150]，min=1, max=150
        ZoneMapTable table = new ZoneMapTable(4);
        table.add(1L);
        table.add(100L);
        table.add(null);
        table.add(150L);
        table.add(200L); // 块 1 = [200]

        // 50 落在块 0 的 [1,150] 内 -> 块不可跳过，但块内并无 50
        QueryResult miss = table.queryEquals(50);
        assertThat(miss.matchedRowIndexes()).isEmpty();
        assertThat(miss.stats().skippedBlocks()).isEqualTo(1); // 只跳过块 1
        assertThat(miss.stats().scannedRows()).isEqualTo(4);
        assertThat(miss.stats().matchedRows()).isZero();

        // 范围 [2, 99] 与块 0 相交，但块内没有值落在其中
        QueryResult rangeMiss = table.queryRange(2, 99);
        assertThat(rangeMiss.matchedRowIndexes()).isEmpty();
        assertThat(rangeMiss.stats().scannedRows()).isEqualTo(4);

        // 空值永不命中等值/范围谓词
        assertThat(table.queryEquals(1).matchedRowIndexes()).containsExactly(0);
        assertThat(table.queryRange(-1000, 1000).matchedRowIndexes())
                .containsExactly(0, 1, 3, 4);
    }

    @Test
    @DisplayName("全空块对任意谓词都可跳过")
    void allNullBlockIsSkipped() {
        ZoneMapTable table = new ZoneMapTable(2);
        table.add(null);
        table.add(null);
        table.add(5L);

        QueryResult result = table.queryEquals(5);
        assertThat(result.matchedRowIndexes()).containsExactly(2);
        assertThat(result.stats().skippedBlocks()).isEqualTo(1);
        assertThat(table.blockStats().get(0).nullCount()).isEqualTo(2);
        assertThat(table.blockStats().get(0).hasValues()).isFalse();
    }

    @Test
    @DisplayName("更新扩大块 min/max：新边界立即可用于跳过判断")
    void updateExpandsBlockBounds() {
        ZoneMapTable table = sortedTable(4, 8); // 块0=[0..3], 块1=[4..7]

        table.set(1, 1000L); // 块0 的 max 扩张到 1000
        assertThat(table.blockStats().get(0).min()).isZero();
        assertThat(table.blockStats().get(0).max()).isEqualTo(1000);

        QueryResult result = table.queryEquals(1000);
        assertThat(result.matchedRowIndexes()).containsExactly(1);

        table.set(6, -50L); // 块1 的 min 收缩到 -50
        assertThat(table.blockStats().get(1).min()).isEqualTo(-50);
        assertThat(table.queryEquals(-50).matchedRowIndexes()).containsExactly(6);
    }

    @Test
    @DisplayName("更新缩小块 min/max：移除边界值后整块重算，统计信息不失效")
    void updateShrinksBlockBoundsViaRecompute() {
        ZoneMapTable table = new ZoneMapTable(4);
        table.add(1L);
        table.add(50L);
        table.add(60L);
        table.add(100L);

        table.set(0, 55L); // 移除 min=1 -> 重算后 min=50
        assertThat(table.blockStats().get(0).min()).isEqualTo(50);
        assertThat(table.blockStats().get(0).max()).isEqualTo(100);

        table.set(3, 70L); // 移除 max=100 -> 重算后 max=70
        assertThat(table.blockStats().get(0).min()).isEqualTo(50);
        assertThat(table.blockStats().get(0).max()).isEqualTo(70);

        // 收缩后的边界必须真正参与跳过：查询 80 不再命中该块
        QueryResult result = table.queryEquals(80);
        assertThat(result.matchedRowIndexes()).isEmpty();
        assertThat(result.stats().skippedBlocks()).isEqualTo(1);
        assertThat(result.stats().scannedRows()).isZero();
    }

    @Test
    @DisplayName("更新涉及空值：空值计数增量维护，min/max 只统计非空值")
    void updateMaintainsNullCount() {
        ZoneMapTable table = new ZoneMapTable(2);
        table.add(10L);
        table.add(20L);

        table.set(0, null);
        assertThat(table.blockStats().get(0).nullCount()).isEqualTo(1);
        assertThat(table.blockStats().get(0).min()).isEqualTo(20);
        assertThat(table.blockStats().get(0).max()).isEqualTo(20);

        table.set(1, null); // 块变为全空
        assertThat(table.blockStats().get(0).nullCount()).isEqualTo(2);
        assertThat(table.blockStats().get(0).hasValues()).isFalse();
        assertThat(table.queryEquals(20).stats().skippedBlocks()).isEqualTo(1);

        table.set(0, 5L); // 全空块重新获得非空值
        assertThat(table.blockStats().get(0).nullCount()).isEqualTo(1);
        assertThat(table.blockStats().get(0).min()).isEqualTo(5);
        assertThat(table.queryEquals(5).matchedRowIndexes()).containsExactly(0);
    }

    @ParameterizedTest(name = "blockSize={0}")
    @ValueSource(ints = {1, 2, 7, 64, 1024})
    @DisplayName("块大小可配置：各种块大小下随机数据 + 随机更新后与全表扫描完全一致")
    void randomizedMatchesFullScan(int blockSize) {
        Random random = new Random(42 + blockSize);
        int rows = 5000;
        ZoneMapTable table = new ZoneMapTable(blockSize);
        List<Long> oracle = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            Long v = randomValue(random);
            table.add(v);
            oracle.add(v);
        }
        // 随机更新（含扩张、收缩、置空），同时维护对照组
        for (int u = 0; u < 2000; u++) {
            int idx = random.nextInt(rows);
            Long v = randomValue(random);
            table.set(idx, v);
            oracle.set(idx, v);
        }
        // 验证统计信息与数据一致
        assertBlockStatsConsistent(table, oracle);
        // 大规模随机等值 + 范围查询，与全表扫描逐一比对
        for (int q = 0; q < 3000; q++) {
            if (random.nextBoolean()) {
                long target = random.nextLong(200);
                assertThat(table.queryEquals(target).matchedRowIndexes())
                        .isEqualTo(table.fullScanEquals(target))
                        .isEqualTo(oracleEquals(oracle, target));
            } else {
                long a = random.nextLong(200);
                long b = random.nextLong(200);
                long lower = Math.min(a, b);
                long upper = Math.max(a, b);
                assertThat(table.queryRange(lower, upper).matchedRowIndexes())
                        .isEqualTo(table.fullScanRange(lower, upper))
                        .isEqualTo(oracleRange(oracle, lower, upper));
            }
        }
    }

    @Test
    @DisplayName("统计：总块数 / 跳过块数 / 扫描条数 / 命中数自洽")
    void statsAreSelfConsistent() {
        ZoneMapTable table = sortedTable(10, 105); // 11 块，最后一块不满

        QueryResult result = table.queryRange(30, 49);
        QueryStats stats = result.stats();

        assertThat(stats.totalBlocks()).isEqualTo(11);
        assertThat(stats.skippedBlocks()).isEqualTo(9);
        assertThat(stats.scannedBlocks()).isEqualTo(2);
        assertThat(stats.scannedRows()).isEqualTo(20);
        assertThat(stats.matchedRows()).isEqualTo(20);
        assertThat(result.matchedRowIndexes()).hasSize((int) stats.matchedRows());
    }

    @Test
    @DisplayName("块大小权衡：有序数据上更小的块带来更高跳过率与更少扫描行")
    void blockSizeTradeoff() {
        int rows = 4096;
        long lower = 1000, upper = 1999; // 覆盖 1/4 数据

        QueryStats coarse = queryOnSorted(256, rows, lower, upper);
        QueryStats fine = queryOnSorted(16, rows, lower, upper);

        assertThat(fine.skippedBlocks()).isGreaterThan(coarse.skippedBlocks());
        assertThat(fine.scannedRows()).isLessThan(coarse.scannedRows());
        // 两者结果都必须与全表扫描一致
        ZoneMapTable reference = sortedTable(16, rows);
        assertThat(reference.queryRange(lower, upper).matchedRowIndexes())
                .isEqualTo(reference.fullScanRange(lower, upper));
    }

    private static QueryStats queryOnSorted(int blockSize, int rows, long lower, long upper) {
        return sortedTable(blockSize, rows).queryRange(lower, upper).stats();
    }

    private static Long randomValue(Random random) {
        return random.nextInt(5) == 0 ? null : random.nextLong(200);
    }

    private static void assertBlockStatsConsistent(ZoneMapTable table, List<Long> oracle) {
        for (BlockStats stats : table.blockStats()) {
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            int nulls = 0, count = 0;
            for (int i = stats.startRowIndex();
                    i < Math.min(stats.startRowIndex() + stats.capacity(), oracle.size()); i++) {
                count++;
                Long v = oracle.get(i);
                if (v == null) {
                    nulls++;
                } else {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
            assertThat(stats.size()).isEqualTo(count);
            assertThat(stats.nullCount()).isEqualTo(nulls);
            if (nulls < count) {
                assertThat(stats.min()).isEqualTo(min);
                assertThat(stats.max()).isEqualTo(max);
            } else {
                assertThat(stats.hasValues()).isFalse();
            }
        }
    }

    private static List<Integer> oracleEquals(List<Long> oracle, long target) {
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < oracle.size(); i++) {
            Long v = oracle.get(i);
            if (v != null && v == target) {
                matched.add(i);
            }
        }
        return matched;
    }

    private static List<Integer> oracleRange(List<Long> oracle, long lower, long upper) {
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < oracle.size(); i++) {
            Long v = oracle.get(i);
            if (v != null && v >= lower && v <= upper) {
                matched.add(i);
            }
        }
        return matched;
    }
}
