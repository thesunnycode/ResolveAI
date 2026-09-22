package com.resolveai.knowledge.web.dto;

public record ReindexRequest(Boolean force) {

    public boolean forced() {
        return Boolean.TRUE.equals(force);
    }
}
