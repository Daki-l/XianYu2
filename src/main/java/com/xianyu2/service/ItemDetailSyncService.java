package com.xianyu2.service;

import com.xianyu2.controller.dto.SyncProgressRespDTO;
import com.xianyu2.controller.dto.ItemDTO;
import java.util.List;

public interface ItemDetailSyncService {
    String startSync(Long accountId, List<ItemDTO> items);
    SyncProgressRespDTO getProgress(String syncId);
    void cancelSync(String syncId);
    boolean isSyncing(Long accountId);
    boolean syncSingleItem(Long accountId, String itemId);
}
