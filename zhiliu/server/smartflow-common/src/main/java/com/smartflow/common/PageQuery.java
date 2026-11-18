package com.smartflow.common;

public record PageQuery(Integer page, Integer size, String keyword,
                        String status, String sort) {

    public int pageNo() {
        return page == null || page < 1 ? 1 : page;
    }

    public int pageSize() {
        if (size == null || size < 1) {
            return 20;
        }
        return Math.min(size, 200);
    }

    public boolean hasKeyword() {
        return keyword != null && !keyword.isBlank();
    }

    public boolean hasStatus() {
        return status != null && !status.isBlank();
    }
}
