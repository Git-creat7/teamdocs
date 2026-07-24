package asia.creat.service.impl;

import asia.creat.mapper.DocumentMapper;
import asia.creat.service.RecentDocumentService;
import asia.creat.utils.CacheClient;
import asia.creat.vo.RecentDocumentVO;
import cn.hutool.core.collection.CollUtil;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static asia.creat.utils.RedisConstants.*;

@Service
public class RecentDocumentServiceImpl implements RecentDocumentService {
    private final CacheClient cacheClient;
    private final DocumentMapper documentMapper;

    public RecentDocumentServiceImpl(CacheClient cacheClient, DocumentMapper documentMapper) {
        this.cacheClient = cacheClient;
        this.documentMapper = documentMapper;
    }

    @Override
    @Async
    public void recordRecentDocument(Long userId, Long documentId) {
        String key = RECENT_DOCUMENT_PREFIX + userId;
        cacheClient.addZSet(key,documentId.toString(),System.currentTimeMillis(),RECENT_DOCUMENT_TTL);
        cacheClient.removeZSetRangeByRank(key,0,-(MAX_RECENT_DOCUMENTS + 1));
    }

    @Override
    public List<RecentDocumentVO> getRecentDocuments(Long userId) {
        String key = RECENT_DOCUMENT_PREFIX + userId;
        Set<TypedTuple<String>> tuples = cacheClient.getZSetReverseRangeWithScores(key, 0, MAX_RECENT_DOCUMENTS - 1);
        if(CollUtil.isEmpty(tuples)) {
            return Collections.emptyList();
        }
        List<String> members = new ArrayList<>();
        List<Long> documentIds = new ArrayList<>();
        Map<Long, LocalDateTime> lastViewedMap = new HashMap<>();
        for (TypedTuple<String> tuple : tuples) {
            if(tuple.getScore()==null || tuple.getValue()==null) {
                continue;
            }
            String member = tuple.getValue();
            Long documentId = Long.valueOf(member);
            members.add(member);
            documentIds.add(documentId);
            lastViewedMap.put(documentId,LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(tuple.getScore().longValue()), ZoneId.systemDefault()));
        }
        if(documentIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<RecentDocumentVO> dbList =
                documentMapper.listAccessibleRecentDocuments(userId, documentIds);
        Map<Long, RecentDocumentVO> byId = dbList.stream()
                .collect(Collectors.toMap(
                        RecentDocumentVO::getDocumentId,
                        v -> v,
                        (a, b) -> a)
                );

        List<RecentDocumentVO> result = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (int i = 0; i < documentIds.size(); i++) {
            RecentDocumentVO vo = byId.get(documentIds.get(i));
            if (vo == null) {
                invalid.add(members.get(i));
                continue;
            }
            vo.setLastViewedAt(lastViewedMap.get(documentIds.get(i)));
            result.add(vo);
        }
        if (!invalid.isEmpty()) {
            cacheClient.removeZSetMembers(key, invalid.toArray(new String[0]));
        }
        return result;


    }
}














