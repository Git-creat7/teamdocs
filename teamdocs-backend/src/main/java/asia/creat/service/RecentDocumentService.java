package asia.creat.service;

import asia.creat.vo.RecentDocumentVO;

import java.util.List;

public interface RecentDocumentService {
    void recordRecentDocument(Long userId, Long documentId);

    List<RecentDocumentVO> getRecentDocuments(Long userId);
}
