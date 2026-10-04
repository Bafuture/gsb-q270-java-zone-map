package com.example.gsb.zonemap;

/**
 * 一次（或累计多次）扫描的统计信息。
 *
 * <ul>
 *   <li>{@link #totalBlocks()} —— 总块数；</li>
 *   <li>{@link #skippedBlocks()} —— 被 zone map 直接跳过的块数；</li>
 *   <li>{@link #scannedBlocks()} —— 实际进入块内二次过滤的块数；</li>
 *   <li>{@link #scannedRows()} —— 实际逐条扫描的行数；</li>
 *   <li>{@link #matchedRows()} —— 二次过滤命中（真正满足谓词）的行数。</li>
 * </ul>
 */
public final class ScanStats {

    private int totalBlocks;
    private int skippedBlocks;
    private long scannedRows;
    private long matchedRows;

    ScanStats() {
    }

    /** 总块数。 */
    public int totalBlocks() {
        return totalBlocks;
    }

    /** 被统计信息直接跳过的块数。 */
    public int skippedBlocks() {
        return skippedBlocks;
    }

    /** 实际扫描的块数（= 总块数 - 跳过块数）。 */
    public int scannedBlocks() {
        return totalBlocks - skippedBlocks;
    }

    /** 实际逐条扫描的行数。 */
    public long scannedRows() {
        return scannedRows;
    }

    /** 块内二次过滤命中（真正满足谓词）的行数。 */
    public long matchedRows() {
        return matchedRows;
    }

    /** 跳过块数占总块数的比例，用于对比跳过效果；无块时返回 0。 */
    public double skipRatio() {
        return totalBlocks == 0 ? 0.0 : (double) skippedBlocks / totalBlocks;
    }

    void totalBlocks(int totalBlocks) {
        this.totalBlocks = totalBlocks;
    }

    void skipBlock() {
        skippedBlocks++;
    }

    void scanRow() {
        scannedRows++;
    }

    void matchRow() {
        matchedRows++;
    }

    void merge(ScanStats other) {
        this.totalBlocks += other.totalBlocks;
        this.skippedBlocks += other.skippedBlocks;
        this.scannedRows += other.scannedRows;
        this.matchedRows += other.matchedRows;
    }

    ScanStats snapshot() {
        ScanStats copy = new ScanStats();
        copy.totalBlocks = totalBlocks;
        copy.skippedBlocks = skippedBlocks;
        copy.scannedRows = scannedRows;
        copy.matchedRows = matchedRows;
        return copy;
    }

    @Override
    public String toString() {
        return "ScanStats{skippedBlocks=" + skippedBlocks + "/" + totalBlocks
                + " (" + String.format(java.util.Locale.ROOT, "%.1f", skipRatio() * 100) + "%)"
                + ", scannedRows=" + scannedRows
                + ", matchedRows=" + matchedRows + '}';
    }
}
