package asia.creat.retrieval;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;

/**
 * 将 MyBatis JDBC 查询超时动态绑定到当前 {@link RetrievalContext} 剩余预算，
 * 避免仅依赖静态 XML timeout="2"，并在剩余预算耗尽时立即中止。
 */
@Component
@Intercepts(@Signature(
        type = StatementHandler.class,
        method = "prepare",
        args = {Connection.class, Integer.class}
))
public class RetrievalQueryTimeoutInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!RetrievalContext.hasBoundedDeadline()) {
            return invocation.proceed();
        }

        long remainingMs = RetrievalContext.remainingMillis();
        if (remainingMs <= 0) {
            throw new IllegalStateException("检索运行已超时");
        }

        Statement statement = (Statement) invocation.proceed();
        if (statement != null) {
            int dynamicSeconds = Math.max(1, (int) Math.ceil(remainingMs / 1000.0));
            int currentTimeout = statement.getQueryTimeout();
            if (currentTimeout <= 0 || dynamicSeconds < currentTimeout) {
                statement.setQueryTimeout(dynamicSeconds);
            }
        }
        return statement;
    }
}
