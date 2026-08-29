package com.pdk.domain.vo;

import lombok.Data;

@Data
public class LicenseExportResult {
    private String fileName;
    private String csv;
    private int recordCount;
}
