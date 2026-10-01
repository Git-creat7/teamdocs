package asia.creat.service;

import asia.creat.security.LoginUser;
import asia.creat.vo.DocumentParseStatusVO;

public interface DocumentParseService {
    void parseDocument(Long documentId);

    DocumentParseStatusVO getStatus(Long spaceId, Long documentId, LoginUser loginUser);

    DocumentParseStatusVO reparse(Long spaceId, Long documentId, LoginUser loginUser);
}
