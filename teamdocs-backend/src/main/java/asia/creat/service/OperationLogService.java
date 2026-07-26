package asia.creat.service;


import asia.creat.entity.OperationLogRecord;
import asia.creat.vo.ActivityVO;

import java.util.List;

public interface OperationLogService {
    void saveLog(OperationLogRecord log);

    List<ActivityVO> listRecentActivities(Long userId, Long spaceId, Integer limit);
}
