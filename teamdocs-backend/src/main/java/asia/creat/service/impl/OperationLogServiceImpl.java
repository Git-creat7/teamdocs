package asia.creat.service.impl;

import asia.creat.entity.OperationLogRecord;
import asia.creat.mapper.OperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationLogServiceImpl implements asia.creat.service.OperationLogService {
    private final OperationLogMapper operationLogMapper;

    public OperationLogServiceImpl(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }


    /*
     *   如果外层存在业务事务
     *    → 暂停外层事务
     *    → 为日志开启一个新事务
     *    → 日志单独提交
     *    → 再恢复外层事务
     *   因此即使业务随后回滚，日志仍然可以保留。
     * */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveLog(OperationLogRecord log){
        operationLogMapper.insert(log);
    }
}
