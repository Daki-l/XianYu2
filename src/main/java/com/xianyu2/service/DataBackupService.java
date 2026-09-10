package com.xianyu2.service;

import com.xianyu2.service.bo.*;

import java.util.List;

public interface DataBackupService {

    List<BackupModuleRespBO> getModules();

    BackupExportRespBO exportData(BackupExportReqBO reqBO);

    BackupImportRespBO importData(BackupImportReqBO reqBO);
}
