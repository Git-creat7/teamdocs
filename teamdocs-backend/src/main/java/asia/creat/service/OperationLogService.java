package asia.creat.service;


import asia.creat.entity.OperationLogRecord;

public interface OperationLogService {
    void saveLog(OperationLogRecord log);
}
