package com.example.gsb.zonemap;

import java.util.Collections;
import java.util.List;

/**
 * 一次查询的结果：命中行的行号、命中值，以及本次扫描的统计信息。
 */
public final class ScanResult {

    private final List<Long> rowIds;
    private final List<Long> values;
    private final ScanStats stats;

    ScanResult(List<Long> rowIds, List<Long> values, ScanStats stats) {
        this.rowIds = Collections.unmodifiableList(rowIds);
        this.values = Collections.unmodifiableList(values);
        this.stats = stats;
    }

    /** 命中行的行号（插入顺序下标），按升序返回。 */
    public List<Long> rowIds() {
        return rowIds;
    }

    /** 命中行的值，与 {@link #rowIds()} 一一对应。 */
    public List<Long> values() {
        return values;
    }

    /** 本次扫描的统计信息（总块数 / 跳过块数 / 实际扫描条数 / 二次过滤命中数）。 */
    public ScanStats stats() {
        return stats;
    }

    @Override
    public String toString() {
        return "ScanResult{matched=" + rowIds.size() + ", " + stats + '}';
    }
}
