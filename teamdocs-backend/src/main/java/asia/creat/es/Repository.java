package asia.creat.es;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface Repository extends ElasticsearchRepository<DocumentDoc,Long> {
}
