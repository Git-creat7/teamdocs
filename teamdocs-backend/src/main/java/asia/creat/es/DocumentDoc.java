package asia.creat.es;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

/**
 * ES 中文档索引实体，用于映射 TeamDocs 中文档的搜索元数据。
 */
@Document(indexName = "teamdocs_document")
@Setting(settingPath = "elasticsearch/document-setting.json")
public class DocumentDoc {
    @Id
    private Long id;

    @MultiField(
        mainField = @Field(type = FieldType.Text,analyzer = "ik_max_word",searchAnalyzer = "ik_smart"),
        otherFields = {
            @InnerField(suffix = "keyword", type = FieldType.Keyword)
        }
    )
    private String name;

    @Field(type =  FieldType.Keyword)
    private Long spaceId;

    @Field(type =  FieldType.Keyword,index = false)
    private String filePath;
}
