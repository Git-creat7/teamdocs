package asia.creat.agent;

import dev.langchain4j.data.message.UserMessage;
import java.util.List;

/** 当前提问的附件文本和图片，不生成正文分块或索引。 */
public class AttachmentMessage extends UserMessage {
    public record Part(String name, String mime, String base64) {
        @Override public String toString() { return "AttachmentPart[redacted]"; }
    }
    private final List<Part> parts;
    public AttachmentMessage(String question, List<Part> parts) {
        super(question);
        this.parts = List.copyOf(parts);
    }
    public List<Part> parts() { return parts; }
    @Override public String toString() { return "AttachmentMessage[redacted]"; }
}
