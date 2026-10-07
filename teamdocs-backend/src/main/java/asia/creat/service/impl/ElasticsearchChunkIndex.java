package asia.creat.service.impl;

import asia.creat.config.ElasticsearchProperties;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.service.ChunkIndex;
import asia.creat.vo.ChunkHitVO;
import asia.creat.vo.ChunkIndexHit;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.HighlighterEncoder;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "teamdocs.elasticsearch", name = "enabled", havingValue = "true")
public class ElasticsearchChunkIndex implements ChunkIndex {
    private final ElasticsearchProperties properties;
    private final DocumentContentMapper documentContentMapper;
    private RestClientTransport transport;
    private ElasticsearchClient client;

    @PostConstruct
    public void start() {
        RestClient rest = RestClient.builder(HttpHost.create(properties.getUrl()))
                .setRequestConfigCallback(config -> config.setConnectTimeout(1000)
                        .setConnectionRequestTimeout(1000).setSocketTimeout(2000))
                .build();

        transport = new RestClientTransport(rest, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    @PreDestroy
    public void close() throws IOException {
        if (transport != null) {
            transport.close();
        }
    }

    @Override
    public boolean enabled() {
        return client != null;
    }

    @Override
    public List<ChunkIndexHit> search(Long spaceId, String keyword, int limit) {
        return searchCandidates(spaceId, null, keyword, Math.min(6, Math.max(1, limit)));
    }

    @Override
    public List<ChunkIndexHit> searchCandidates(Long spaceId, Long documentId, String keyword, int limit) {
        try {
            var response = client.search(request -> request.index(properties.getIndex())
                            .size(Math.min(20, Math.max(1, limit)))
                            .query(query -> query.bool(bool -> {
                                bool.must(must -> must.match(match -> match.field("content").query(keyword)))
                                        .filter(filter -> filter.term(term -> term.field("space_id").value(spaceId)));

                                if (documentId != null) {
                                    bool.filter(filter -> filter.term(term -> term.field("document_id").value(documentId)));
                                }

                                return bool;
                            }))
                            .highlight(highlight -> highlight.encoder(HighlighterEncoder.Html)
                                    .preTags("<mark>").postTags("</mark>")
                                    .fields("content", field -> field.fragmentSize(160).numberOfFragments(1))),
                    IndexedChunk.class);
            List<ChunkIndexHit> result = new ArrayList<>();

            for (var hit : response.hits().hits()) {
                IndexedChunk source = hit.source();

                if (source == null || source.getChunkId() == null || source.getDocumentId() == null
                        || source.getParseVersion() == null) {
                    continue;
                }

                List<String> fragments = hit.highlight().get("content");

                result.add(new ChunkIndexHit(source.getChunkId(), source.getDocumentId(), source.getParseVersion(),
                        fragments == null || fragments.isEmpty() ? null : fragments.get(0)));
            }

            return result;
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("Elasticsearch 检索失败", e);
        }
    }

    /** 不使用解析任务传来的旧快照；事务提交后重新读取 MySQL 当前状态。 */
    @Override
    public synchronized void syncDocument(Long documentId) {
        try {
            List<ChunkHitVO> rows = documentContentMapper.listIndexableDocumentChunks(documentId);

            ensureIndex();

            var deletion = client.deleteByQuery(request -> request.index(properties.getIndex())
                    .conflicts(Conflicts.Proceed).refresh(true)
                    .query(query -> query.term(term -> term.field("document_id").value(documentId))));

            if (deletion.timedOut() || !deletion.failures().isEmpty() || deletion.versionConflicts() > 0) {
                throw new IOException("Elasticsearch 文档清理未完成");
            }

            write(rows);
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("Elasticsearch 同步失败", e);
        }
    }

    /** 清除失效条目，再按主键有界扫描。可以重复执行，失败时检索仍可走 MySQL。 */
    @Override
    public synchronized int rebuild() {
        try {
            if (client.indices().exists(request -> request.index(properties.getIndex())).value()) {
                client.indices().delete(request -> request.index(properties.getIndex()));
            }

            ensureIndex();

            long after = 0;
            int count = 0;

            while (true) {
                List<ChunkHitVO> rows = documentContentMapper.listIndexableChunks(after, 200);

                if (rows.isEmpty()) {
                    return count;
                }

                write(rows);
                after = rows.get(rows.size() - 1).getChunkId();
                count += rows.size();
            }
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("Elasticsearch 重建失败", e);
        }
    }

    private void write(List<ChunkHitVO> rows) throws IOException {
        if (rows.isEmpty()) {
            return;
        }

        List<BulkOperation> operations = new ArrayList<>();

        for (ChunkHitVO row : rows) {
            IndexedChunk source = IndexedChunk.builder()
                    .spaceId(row.getSpaceId())
                    .documentId(row.getDocumentId())
                    .chunkId(row.getChunkId())
                    .chunkIndex(row.getChunkIndex())
                    .parseVersion(row.getParseVersion())
                    .content(row.getExcerpt())
                    .build();

            operations.add(BulkOperation.of(op -> op.index(index -> index.index(properties.getIndex())
                    .id(row.getDocumentId() + "_" + row.getChunkIndex()).document(source))));
        }

        var response = client.bulk(request -> request.operations(operations).refresh(Refresh.True));

        if (response.errors()) {
            throw new IOException("Elasticsearch 部分分块写入失败");
        }
    }

    @Override
    public DocumentStatus documentStatus(Long spaceId, Long documentId, Integer version, long expected) {
        try {
            if (!client.indices().exists(q -> q.index(properties.getIndex())).value()) {
                return new DocumentStatus("MISSING", 0, "ES 服务可达，正文索引尚未创建");
            }
            long total = client.count(q -> q.index(properties.getIndex()).query(query -> query.bool(b -> b
                    .filter(f -> f.term(t -> t.field("space_id").value(spaceId)))
                    .filter(f -> f.term(t -> t.field("document_id").value(documentId)))))).count();
            long current = client.count(q -> q.index(properties.getIndex()).query(query -> query.bool(b -> b
                    .filter(f -> f.term(t -> t.field("space_id").value(spaceId)))
                    .filter(f -> f.term(t -> t.field("document_id").value(documentId)))
                    .filter(f -> f.term(t -> t.field("parse_version").value(version)))))).count();
            boolean synced = total == current && current == expected;
            return new DocumentStatus(synced ? "SYNCED" : "OUTDATED", current,
                    synced ? "当前版本分块数量一致" : "索引缺失分块或包含旧版本，请重新同步");
        } catch (Exception error) {
            return new DocumentStatus("ERROR", 0, "ES 索引检查失败，请检查服务连接与索引配置");
        }
    }

    private void ensureIndex() throws IOException {
        if (client.indices().exists(request -> request.index(properties.getIndex())).value()) {
            return;
        }

        try {
            client.indices().create(request -> request.index(properties.getIndex())
                    .settings(settings -> settings.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(mapping -> mapping
                            .properties("space_id", field -> field.long_(value -> value))
                            .properties("document_id", field -> field.long_(value -> value))
                            .properties("chunk_id", field -> field.long_(value -> value))
                            .properties("chunk_index", field -> field.integer(value -> value))
                            .properties("parse_version", field -> field.integer(value -> value))
                            .properties("content", field -> field.text(value -> value
                                    .analyzer("ik_max_word").searchAnalyzer("ik_smart")))));
        } catch (ElasticsearchException e) {
            // 多个后台进程首次创建同一索引是正常竞争；IK 缺失等错误不能静默改用其他分词器。
            if (!"resource_already_exists_exception".equals(e.error().type())) {
                throw e;
            }
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndexedChunk {
        @JsonProperty("space_id")
        private Long spaceId;
        @JsonProperty("document_id")
        private Long documentId;
        @JsonProperty("chunk_id")
        private Long chunkId;
        @JsonProperty("chunk_index")
        private Integer chunkIndex;
        @JsonProperty("parse_version")
        private Integer parseVersion;
        private String content;
    }
}
