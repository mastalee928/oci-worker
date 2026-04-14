package com.ociworker.model.params;

import lombok.Data;

@Data
public class PageParams {
    private int current = 1;
    private int size = 10;
    private String keyword;
    private String status;
}
