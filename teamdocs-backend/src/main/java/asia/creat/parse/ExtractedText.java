package asia.creat.parse;

import lombok.Getter;

import java.util.List;

@Getter
public final class ExtractedText {
    private final boolean skipped;
    private final String reason;
    private final List<Segment> segments;

    private ExtractedText(boolean skipped, String reason, List<Segment> segments) {
        this.skipped = skipped;
        this.reason = reason;
        this.segments = segments;
    }

    public static ExtractedText skipped(String reason) {
        return new ExtractedText(true, reason, List.of());
    }

    public static ExtractedText of(List<Segment> segments) {
        return new ExtractedText(false, null, List.copyOf(segments));
    }

    public record Segment(Integer pageNumber, String text) {
    }
}
