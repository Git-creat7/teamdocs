/*
* 建立两个全文索引，分别针对文档的name和description字段，使用ngram解析器，以提高搜索效率和准确性。
*/

-- 文档name
ALTER TABLE document
    ADD FULLTEXT INDEX ft_name (name) WITH PARSER ngram;

-- 文档description
ALTER TABLE document
    ADD FULLTEXT INDEX ft_description (description) WITH PARSER ngram;