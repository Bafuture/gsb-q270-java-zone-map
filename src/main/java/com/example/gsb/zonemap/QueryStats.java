package com.example.gsb.zonemap;

/**
 * 单次 查询的执行统计：总块数、被 zone map 跳过的块数、
 * 实际逐条扫描的行数、以及块内二次过滤命中的行数。
 */
public final class QueryStats {

    private final int totalBlocks;
    private final int skippedBlocks;
    private final long scannedRows;
    private final long matchedRows;

    QueryStats(int totalBlocks, int skippedBlocks, long scannedRows, long matchedRows) {
        this.totalBlocks = totalBlocks;
        this.skippedBlocks = skippedBlocks;
        this.scannedRows = scannedRows;
        this.matchedRows = matchedRows;
    }

    /** 表中总块数。 */
    public int totalBlocks() {
        return totalBlocks;
    }

    /** 因 min/max 区间与谓词不相交而被整体跳过的块数。 */
    public int skippedBlocks() {
        return skippedBlocks;
    }

    /** 未被跳过块数（需要进入块内逐条判定的块数）。 */
    public int scannedBlocks() {
        return totalBlocks - skippedBlocks;
    }

    /** 块内二次过滤实际逐条检查的行数。 */
    public long scannedRows() {
        return scannedRows;
    }

    /** 二次过滤最终命中的行数。 */
    public long matchedRows() {
        return matchedRows;
    }

    /** 跳过率：skippedBlocks / totalBlocks，无块时为 0。 */
    public double skipRatio() {
        return totalBlocks == 0 ? 0.0 : (double) skippedBlocks / totalBlocks;
    }

    @Override
    public String toString() {
        return "QueryStats[totalBlocks=" + totalBlocks
                + ", skippedBlocks=" + skippedBlocks
                + ", scannedRows=" + scannedRows
                + ", matchedRows=" + matchedRows
                + ", skipRatio=" + String.format("%.2f", skipRatio()) + "]";
    }
}
