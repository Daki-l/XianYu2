package com.xianyu2.controller.dto;

import lombok.Data;

@Data
public class VersionInfoRespDTO {
    private String currentVersion;
    private String latestVersion;
    private Boolean hasUpdate;
    private Boolean updateEnabled;
    private String updateContent;
    private String publishedAt;
    private String downloadUrl;
    private String releaseTag;
    private Long manifestAssetId;
}
