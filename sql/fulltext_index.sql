/*
* 建立两个全文索引，分别针对文档的name和description字段，使用ngram解析器，以提高搜索效率和准确性。
*
* 注意：必须先关闭 InnoDB 全文停用词再建索引。
* ngram 的停用词规则是「token 包含停用词即整体排除」，默认停用词表含 "i"，
* 导致 "zip"→"zi"/"ip" 这类含 i 的英文 bigram 全部不进索引，搜不出来。
* SET PERSIST 写入 mysqld-auto.cnf，重启后仍生效；已建索引的历史库需重建索引才回填。
*/

SET PERSIST innodb_ft_enable_stopword = OFF;

-- 文档name
ALTER TABLE document
    ADD FULLTEXT INDEX ft_name (name) WITH PARSER ngram;

-- 文档description
ALTER TABLE document
    ADD FULLTEXT INDEX ft_description (description) WITH PARSER ngram;
