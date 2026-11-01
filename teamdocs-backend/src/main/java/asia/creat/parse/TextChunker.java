package asia.creat.parse;

import asia.creat.entity.DocumentContent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextChunker {
    private static final Pattern HEADING_PATTERN = Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*$");

    private TextChunker() {
    }

    public static class HeadingTracker {
        private final List<HeadingEntry> stack = new ArrayList<>();

        public record HeadingEntry(int level, String text) {}

        public void onHeading(int level, String text) {
            while (!stack.isEmpty() && stack.get(stack.size() - 1).level() >= level) {
                stack.remove(stack.size() - 1);
            }
            stack.add(new HeadingEntry(level, text.trim()));
        }

        public String currentPath() {
            if (stack.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < stack.size(); i++) {
                if (i > 0) sb.append(" > ");
                sb.append(stack.get(i).text());
            }
            String path = sb.toString();
            return path.length() > 255 ? path.substring(0, 255) : path;
        }
    }

    private record HeadingOccurrence(int startOffset, int level, String text) {}

    public static List<DocumentContent> chunk(String text, Integer pageNumber, int chunkSize, int overlap) {
        return chunk(text, pageNumber, chunkSize, overlap, new HeadingTracker());
    }

    public static List<DocumentContent> chunk(String text, Integer pageNumber, int chunkSize, int overlap, HeadingTracker tracker) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("分块参数不合法");
        }

        String normalized = normalize(text);

        if (normalized.isEmpty()) {
            return List.of();
        }

        if (tracker == null) {
            tracker = new HeadingTracker();
        }

        List<HeadingOccurrence> headings = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(normalized);
        while (matcher.find()) {
            headings.add(new HeadingOccurrence(matcher.start(), matcher.group(1).length(), matcher.group(2)));
        }
        int headingScanIndex = 0;

        List<DocumentContent> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;

        while (start < normalized.length()) {
            while (headingScanIndex < headings.size() && headings.get(headingScanIndex).startOffset() <= start) {
                HeadingOccurrence h = headings.get(headingScanIndex);
                tracker.onHeading(h.level(), h.text());
                headingScanIndex++;
            }

            int end = Math.min(start + chunkSize, normalized.length());

            if (end < normalized.length()) {
                // 1. 标题边界优先：若在 [start + chunkSize/3, end] 范围内有标题，在标题前断开
                for (HeadingOccurrence h : headings) {
                    if (h.startOffset() > start + chunkSize / 3 && h.startOffset() <= end) {
                        end = h.startOffset();
                        break;
                    }
                }

                // 2. 段落边界优先
                int paragraphEnd = normalized.lastIndexOf('\n', end - 1) + 1;

                if (paragraphEnd > start + chunkSize / 2 && paragraphEnd - start > overlap) {
                    end = paragraphEnd;
                }

                // 3. 代码块与表格行边界保护
                end = adjustForCodeBlock(normalized, start, end, chunkSize);
                end = adjustForTableRow(normalized, start, end);

                if (Character.isHighSurrogate(normalized.charAt(end - 1))
                        && Character.isLowSurrogate(normalized.charAt(end))) {
                    end--;
                }

                if (end <= start) {
                    end = Math.min(start + 2, normalized.length());
                }
            }

            String content = normalized.substring(start, end);
            DocumentContent chunk = DocumentContent.builder()
                    .chunkIndex(index++)
                    .heading(tracker.currentPath())
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

            // 如果切分点刚好在标题起点，不跨章节回退重叠，确保新章节以标题开始
            for (HeadingOccurrence h : headings) {
                if (end == h.startOffset()) {
                    next = end;
                    break;
                }
            }

            // 若重叠起点落在表格行中间，调整对齐到表格行边界
            if (next > start && next < normalized.length()) {
                int prevNl = next > 0 ? normalized.lastIndexOf('\n', next - 1) : -1;
                int nextNl = normalized.indexOf('\n', next);
                int lineStart = prevNl == -1 ? 0 : prevNl + 1;
                int lineEnd = nextNl == -1 ? normalized.length() : nextNl;
                if (lineStart <= lineEnd && normalized.substring(lineStart, lineEnd).contains("|")) {
                    if (nextNl != -1 && nextNl + 1 < end) {
                        next = nextNl + 1;
                    } else if (lineStart > start) {
                        next = lineStart;
                    }
                }
            }

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

    private static int adjustForCodeBlock(String text, int start, int end, int chunkSize) {
        int fenceCount = 0;
        int idx = 0;
        while ((idx = text.indexOf("```", idx)) != -1 && idx < end) {
            fenceCount++;
            idx += 3;
        }

        if (fenceCount % 2 == 1) {
            int closingFence = text.indexOf("```", end);
            if (closingFence != -1) {
                int endOfClosing = text.indexOf('\n', closingFence + 3);
                int candidate = endOfClosing != -1 ? endOfClosing + 1 : text.length();
                if (candidate - start <= (int) (chunkSize * 1.25)) {
                    return candidate;
                }
            }

            int openingFence = text.lastIndexOf("```", end - 1);
            if (openingFence > start + chunkSize / 3) {
                int lineBefore = text.lastIndexOf('\n', openingFence - 1);
                return lineBefore != -1 && lineBefore > start ? lineBefore + 1 : openingFence;
            }

            int lineBreak = text.lastIndexOf('\n', end - 1) + 1;
            if (lineBreak > start + chunkSize / 2) {
                return lineBreak;
            }
        }

        return end;
    }

    private static int adjustForTableRow(String text, int start, int end) {
        if (end <= start || end >= text.length()) {
            return end;
        }

        if (text.charAt(end - 1) == '\n') {
            return end;
        }

        int prevNewline = text.lastIndexOf('\n', end - 1);
        int nextNewline = text.indexOf('\n', end);
        int lineStart = prevNewline == -1 ? 0 : prevNewline + 1;
        int lineEnd = nextNewline == -1 ? text.length() : nextNewline;

        String line = text.substring(lineStart, lineEnd);
        if (line.contains("|")) {
            if (nextNewline != -1 && nextNewline + 1 - start <= end - start + 80) {
                return nextNewline + 1;
            } else if (lineStart > start) {
                return lineStart;
            }
        }

        return end;
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

    /** 中文按字计，其余约 4 个字符 1 token。只作粗估，不代表模型实际用量。 */
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
