package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.AgentWorker;
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
        asia.creat.agent.AgentJson json = new asia.creat.agent.AgentJson(new com.fasterxml.jackson.databind.ObjectMapper());
        // 构造字符串内部含有真实换行符与制表符（ASCII 10, 9）的 JSON
        String rawWithNewlines = "{\"answer\": \"# 标题\n\n- 列表项1\n\t- 子项带有Tab\n\n```\n代码块内容\n```\", \"citations\": []}";
        var node = json.object(rawWithNewlines, java.util.Set.of("answer", "citations"));
        assertNotNull(node);
        assertTrue(node.get("answer").asText().contains("代码块内容"));
        assertTrue(node.get("citations").isArray());
    }

    @Test
    @DisplayName("AgentJson.object: 支持反斜杠转义任意非标准字符（如 \\# \\* \\'）")
    void testAgentJsonWithBackslashEscapingAnyChar() {
        asia.creat.agent.AgentJson json = new asia.creat.agent.AgentJson(new com.fasterxml.jackson.databind.ObjectMapper());
        String textWithUnusualEscapes = "{\"answer\": \"Markdown转义: \\# 标题 \\*星号\\* \\'单引号\\'\", \"citations\": []}";
        var node = json.object(textWithUnusualEscapes, java.util.Set.of("answer", "citations"));
        assertNotNull(node);
        assertTrue(node.get("answer").asText().contains("Markdown转义"));
    }
}

