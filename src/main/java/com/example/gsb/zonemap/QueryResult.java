package com.example.gsb.zonemap;

import java.util.List;

/**
 * 一次查询的结果：命中行下标（升序）与执行统计。
 */
public final class QueryResult {

    private final List<Integer> matchedRowIndexes;
    private final QueryStats stats;

    QueryResult(List<Integer> matchedRowIndexes, QueryStats stats) {
        this.matchedRowIndexes = List.copyOf(matchedRowIndexes);
        this.stats = stats;
    }

    /** 命中行的下标，按升序返回。 */
    public List<Integer> matchedRowIndexes() {
        return matchedRowIndexes;
    }

    public QueryStats stats() {
        return stats;
    }
}
