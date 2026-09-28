package asia.creat.agent;

public class AgentFailure extends RuntimeException {
    public AgentFailure(String code) { super(code); }
    public String code() { return getMessage(); }
}
