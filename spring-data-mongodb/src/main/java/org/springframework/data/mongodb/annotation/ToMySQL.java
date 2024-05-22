package org.springframework.data.mongodb.annotation;


import org.springframework.stereotype.Indexed;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Indexed
@Inherited
public @interface ToMySQL {

    String table() default "";

    String database() default "";

}
