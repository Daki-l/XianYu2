package com.xianyu2.service.bo;

import lombok.Data;

import java.util.List;

@Data
public class BackupExportReqBO {
    private List<String> modules;
}
