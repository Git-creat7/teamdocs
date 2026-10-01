package asia.creat.retrieval;

/** 仅承载可降级的检索服务错误，不包含远端响应或私有正文。 */
public class RetrievalException extends RuntimeException {
    /**
     * 创建脱敏错误。
     * @param message 可安全展示的错误说明
     */
    public RetrievalException(String message) {
        super(message);
    }
}
