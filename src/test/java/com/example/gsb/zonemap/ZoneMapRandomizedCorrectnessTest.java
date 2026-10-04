package com.example.gsb.zonemap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 要求 5：大规模随机数据 + 随机更新 + 随机查询下，
 * zone map 查询结果必须与全表扫描结果完全一致。
 */
class ZoneMapRandomizedCorrectnessTest {

    private static final long SEED = 20261004L;
    private static final int ROWS = 20_000;
    private static final int DOMAIN = 5_000;
    private static final double NULL_PROBABILITY = 0.15;
    private static final int ROUNDS = 8;
    private static final int UPDATES_PER_ROUND = 100;
    private static final int QUERIES_PER_ROUND = 60;

    private static Long randomValue(Random random) {
        return random.nextDouble() < NULL_PROBABILITY ? null : (long) random.nextInt(DOMAIN);
    }

    @Test
    void zoneMapResultsAlwaysMatchFullScan() {
        for (int blockSize : new int[] {16, 128, 2048}) {
            verifyAgainstFullScan(blockSize);
        }
    }

    private static void verifyAgainstFullScan(int blockSize) {
        Random random = new Random(SEED + blockSize);
        ZoneMapTable table = new ZoneMapTable(blockSize);
        for (int i = 0; i < ROWS; i++) {
            table.insert(randomValue(random));
        }

        for (int round = 0; round < ROUNDS; round++) {
            // 随机更新：可能扩大或缩小块的 min/max，也可能引入/消除空值
            for (int u = 0; u < UPDATES_PER_ROUND; u++) {
                table.update(random.nextInt(ROWS), randomValue(random));
            }

            for (int q = 0; q < QUERIES_PER_ROUND; q++) {
                if (random.nextBoolean()) {
                    verifyEqualityQuery(table, random);
                } else {
                    verifyRangeQuery(table, random);
                }
            }
        }
    }

    private static void verifyEqualityQuery(ZoneMapTable table, Random random) {
        long target = random.nextInt(DOMAIN);
        ScanResult result = table.queryEquals(target);

        assertThat(result.rowIds())
                .as("equality query %d must match full scan", target)
                .isEqualTo(table.fullScan(v -> v == target));
        assertStatsInvariants(result);
    }

    private static void verifyRangeQuery(ZoneMapTable table, Random random) {
        long a = random.nextInt(DOMAIN);
        long b = random.nextInt(DOMAIN);
        long lower = Math.min(a, b);
        long upper = Math.max(a, b);
        boolean lowerInclusive = lower == upper || random.nextBoolean();
        boolean upperInclusive = lower == upper || random.nextBoolean();

        ScanResult result = table.queryRange(lower, lowerInclusive, upper, upperInclusive);

        assertThat(result.rowIds())
                .as("range query (%s%d, %d%s) must match full scan",
                        lowerInclusive ? "[" : "(", lower, upper, upperInclusive ? "]" : ")")
                .isEqualTo(table.fullScan(v -> inRange(v, lower, lowerInclusive, upper, upperInclusive)));
        assertStatsInvariants(result);
    }

    private static boolean inRange(long v, long lower, boolean lowerInclusive,
                                   long upper, boolean upperInclusive) {
        boolean aboveLower = lowerInclusive ? v >= lower : v > lower;
        boolean belowUpper = upperInclusive ? v <= upper : v < upper;
        return aboveLower && belowUpper;
    }

    private static void assertStatsInvariants(ScanResult result) {
        ScanStats stats = result.stats();
        assertThat(stats.skippedBlocks() + stats.scannedBlocks())
                .isEqualTo(stats.totalBlocks());
        assertThat(stats.matchedRows()).isEqualTo(result.rowIds().size());
        assertThat(stats.scannedRows()).isGreaterThanOrEqualTo(stats.matchedRows());
        assertThat(stats.skippedBlocks()).isBetween(0, stats.totalBlocks());
    }
}
