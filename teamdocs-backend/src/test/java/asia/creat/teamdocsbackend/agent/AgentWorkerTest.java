package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.AgentFailure;
import asia.creat.agent.AgentJson;
import asia.creat.agent.AgentWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentWorkerTest {

    @Test
    @DisplayName("cleanAnswerJson: 标准合法 JSON 原样返回")
    void testStandardJson() {
        String input = "{\"answer\": \"你好！\", \"citations\": []}";

        assertEquals(input, AgentWorker.cleanAnswerJson(input));
    }

    @Test
    @DisplayName("cleanAnswerJson: Markdown 代码块包裹应正确提取")
    void testMarkdownCodeFence() {
        String input = "```json\n{\"answer\": \"你好！\", \"citations\": []}\n```";

        assertEquals("{\"answer\": \"你好！\", \"citations\": []}", AgentWorker.cleanAnswerJson(input));
    }

    @Test
    @DisplayName("cleanAnswerJson: 代码块前后带有思维链或提示说明文本")
    void testMarkdownWithLeadingAndTrailingComments() {
        String input = "好的，为你整理的回答如下：\n```json\n{\"answer\": \"你好！\", \"citations\": []}\n```\n希望对你有所帮助！";

        assertEquals("{\"answer\": \"你好！\", \"citations\": []}", AgentWorker.cleanAnswerJson(input));
    }

    @Test
    @DisplayName("cleanAnswerJson: 无代码块但前部有前导文本 (如 Here is the response:)")
    void testPreambleWithoutFences() {
        String input = "Here is the response: {\"answer\": \"这是一个回答\", \"citations\": [\"C1\"]}";

        assertEquals("{\"answer\": \"这是一个回答\", \"citations\": [\"C1\"]}", AgentWorker.cleanAnswerJson(input));
    }

    @Test
    @DisplayName("cleanAnswerJson: 前后均有多余文本及换行")
    void testLeadingAndTrailingTextWithoutFences() {
        String input = "Thinking process finished.\n{\"answer\": \"核心内容\", \"citations\": []}\nEnjoy your day!";

        assertEquals("{\"answer\": \"核心内容\", \"citations\": []}", AgentWorker.cleanAnswerJson(input));
    }

    @Test
    @DisplayName("cleanAnswerJson: answer 内部包含代码块或花括号")
    void testNestedBracesInAnswer() {
        String input = "Here is the code answer: {\"answer\": \"示例代码: function() { return { a: 1 }; }\", \"citations\": []} done.";

        assertEquals("{\"answer\": \"示例代码: function() { return { a: 1 }; }\", \"citations\": []}", AgentWorker.cleanAnswerJson(input));
    }

    /** 合法外壳中的代码块不应被当成外层包装剥离。 */
    @Test
    void preservesCodeFencesInsideJsonStrings() throws Exception {
        var mapper = new ObjectMapper();
        var json = new AgentJson(mapper);
        for (String answer : List.of(
                "示例：\n```python\nprint(1)\n```",
                "```json\n{\"nested\":{\"ok\":true}}\n```",
                "```java\nString s = \"} and \\\" {\";\n```\n再看：\n```sh\necho done\n```",
                "只有一个标记 ```，没有结束围栏")) {
            String input = mapper.writeValueAsString(Map.of("answer", answer, "citations", List.of()));
            assertEquals(input, AgentWorker.cleanAnswerJson(input));
            assertEquals(answer, json.object(AgentWorker.cleanAnswerJson(input), Set.of("answer", "citations"))
                    .path("answer").asText());
            assertEquals(input, AgentWorker.cleanAnswerJson("说明：\n```json\n" + input + "\n```\n结束。"));
        }
    }

    /** 只剥离对象外的说明文本，不改变字符串中的换行、引号和大括号。 */
    @Test
    void preservesUnescapedMarkdownInsideWrappedJson() {
        String input = "{\"answer\":\"示例\n```\n{ code }\n```\",\"citations\":[]}";
        assertEquals(input, AgentWorker.cleanAnswerJson(input));
        assertEquals(input, AgentWorker.cleanAnswerJson("```json\n" + input + "\n```"));
    }

    /** 不从数组、多个对象或截断响应中挑一个看似可用的对象。 */
    @Test
    void ambiguousAndTruncatedResponsesStayInvalid() {
        var json = new AgentJson(new ObjectMapper());
        String object = "{\"answer\":\"ok\",\"citations\":[]}";
        for (String input : List.of("[" + object + "]", object + object, object + " []",
                "```json\n[" + object + "]\n```", "{\"answer\":\"```json\\n{\\\"ok\\\":true}\\n```")) {
            assertThrows(AgentFailure.class,
                    () -> json.object(AgentWorker.cleanAnswerJson(input), Set.of("answer", "citations")));
        }
    }

    @Test
    @DisplayName("cleanAnswerJson: null 或空串安全处理")
    void testNullAndEmpty() {
        assertEquals("", AgentWorker.cleanAnswerJson(null));
        assertEquals("", AgentWorker.cleanAnswerJson("   "));
    }

    @Test
    @DisplayName("SYSTEM_TEMPLATE 包含时间和空间占位符且可正确格式化")
    void testSystemTemplateFormat() {
        assertNotNull(AgentWorker.SYSTEM_TEMPLATE);
        assertTrue(AgentWorker.SYSTEM_TEMPLATE.contains("=== 当前系统环境与时间 ==="));
        assertTrue(AgentWorker.SYSTEM_TEMPLATE.contains("=== 当前空间背景信息 ==="));
        
        String formatted = String.format(AgentWorker.SYSTEM_TEMPLATE, "时间信息测试", "空间背景测试");

        assertTrue(formatted.contains("时间信息测试"));
        assertTrue(formatted.contains("空间背景测试"));
        assertEquals(AgentWorker.SYSTEM_TEMPLATE, AgentWorker.SYSTEM);
    }

    @Test
    @DisplayName("AgentJson.object: 支持长篇 Markdown 包含未转义换行符与制表符")
    void testAgentJsonWithUnescapedControlChars() {
        AgentJson json = new AgentJson(new ObjectMapper());
        // 构造字符串内部含有真实换行符与制表符（ASCII 10, 9）的 JSON
        String rawWithNewlines = "{\"answer\": \"# 标题\n\n- 列表项1\n\t- 子项带有Tab\n\n```\n代码块内容\n```\", \"citations\": []}";
        var node = json.object(rawWithNewlines, Set.of("answer", "citations"));

        assertNotNull(node);
        assertTrue(node.get("answer").asText().contains("代码块内容"));
        assertTrue(node.get("citations").isArray());
    }

    @Test
    @DisplayName("AgentJson.object: 支持反斜杠转义任意非标准字符（如 \\# \\* \\'）")
    void testAgentJsonWithBackslashEscapingAnyChar() {
        AgentJson json = new AgentJson(new ObjectMapper());
        String textWithUnusualEscapes = "{\"answer\": \"Markdown转义: \\# 标题 \\*星号\\* \\'单引号\\'\", \"citations\": []}";
        var node = json.object(textWithUnusualEscapes, Set.of("answer", "citations"));

        assertNotNull(node);
        assertTrue(node.get("answer").asText().contains("Markdown转义"));
    }
}

