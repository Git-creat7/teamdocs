package asia.creat.anno;

import asia.creat.entity.SpaceRole;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireSpaceRole {
    SpaceRole[] value() default {
            SpaceRole.OWNER,
            SpaceRole.ADMIN,
            SpaceRole.MEMBER
    };
}
