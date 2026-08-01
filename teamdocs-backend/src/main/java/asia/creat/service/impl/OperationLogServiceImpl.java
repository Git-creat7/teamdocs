package asia.creat.service.impl;

import asia.creat.entity.OperationLogRecord;
import asia.creat.mapper.OperationLogMapper;
import asia.creat.service.OperationLogService;
import asia.creat.vo.ActivityVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {
    private final OperationLogMapper operationLogMapper;

    /*
     * 如果外层存在业务事务
     * → 暂停外层事务
     * → 为日志开启一个新事务
     * → 日志单独提交
     * → 再恢复外层事务
     * 因此即使业务随后回滚，日志仍然可以保留。
     * */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveLog(OperationLogRecord log){
        operationLogMapper.insert(log);
    }

    @Override
    public List<ActivityVO> listRecentActivities(Long userId, Long spaceId, Integer limit) {
        int capped = (limit == null || limit < 1) ? 20 : Math.min(limit, 50);
        return operationLogMapper.selectRecentActivities(userId, spaceId, capped);
    }
}
