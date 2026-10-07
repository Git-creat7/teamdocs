package asia.creat.parse;

import asia.creat.entity.DocumentContent;

import java.util.ArrayList;
import java.util.List;

public final class TextChunker {
    private TextChunker() {
    }

    public static List<DocumentContent> chunk(String text, Integer pageNumber, int chunkSize, int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("分块参数不合法");
        }

        String normalized = normalize(text);

        if (normalized.isEmpty()) {
            return List.of();
        }

        List<DocumentContent> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;

        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());

            if (end < normalized.length()) {
                int paragraphEnd = normalized.lastIndexOf('\n', end - 1) + 1;

                if (paragraphEnd > start + chunkSize / 2 && paragraphEnd - start > overlap) {
                    end = paragraphEnd;
                }

                if (Character.isHighSurrogate(normalized.charAt(end - 1))
                        && Character.isLowSurrogate(normalized.charAt(end))) {
                    end--;
                }

                if (end == start) {
                    end = Math.min(start + 2, normalized.length());
                }
            }

            String content = normalized.substring(start, end);
            DocumentContent chunk = DocumentContent.builder()
                    .chunkIndex(index++)
                    .content(content)
                    .tokenCount(estimateTokens(content))
                    .pageNumber(pageNumber)
                    .charStart(start)
                    .charEnd(end)
                    .build();

            chunks.add(chunk);

            if (end >= normalized.length()) {
                break;
            }

            int next = end - overlap;

            if (next > 0 && next < normalized.length() && Character.isLowSurrogate(normalized.charAt(next))
                    && Character.isHighSurrogate(normalized.charAt(next - 1))) {
                next++;
            }

            if (next <= start) {
                next = end;
            }

            start = next;
        }

        return chunks;
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');

        if (normalized.indexOf('\0') >= 0) {
            normalized = normalized.replace("\0", "");
        }

        return normalized.trim();
    }

    /** 中文按字计，其余约 4 个字符 1 token。只作粗估，不代表模型用量。 */
    public static int estimateTokens(String text) {
        int cjk = 0;
        int other = 0;

        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);

            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                cjk++;
            } else {
                other++;
            }

            i += Character.charCount(codePoint);
        }

        return Math.max(1, cjk + (other + 3) / 4);
    }

}
