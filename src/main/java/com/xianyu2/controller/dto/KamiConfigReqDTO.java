package com.xianyu2.controller.dto;

import lombok.Data;

@Data
public class KamiConfigReqDTO {

    private Long id;

    private String aliasName;

    private String sourceType;

    private String externalApiUrl;

    private String externalApiHeaders;

    private String externalApiBody;

    private String externalApiResultPath;

    private String externalApiOrderIdPath;

    private Integer externalApiTimeoutSeconds;

    private Integer alertEnabled;

    private Integer alertThresholdType;

    private Integer alertThresholdValue;

    private String alertEmail;
}
