package asia.creat.anno;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/*
*
*  @OperationLog(value = "操作名称", resourceType = "资源类型")
*
* 只能标注在方法上
* 运行时保留
* */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {
    /*
    * 操作名称 资源类型
    * */
    String value();
    String resourceType() default "";
    String resourceName() default "";
}
