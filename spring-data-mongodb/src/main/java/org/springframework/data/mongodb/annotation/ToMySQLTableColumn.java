package org.springframework.data.mongodb.annotation;


import org.springframework.stereotype.Indexed;

import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Indexed
@Inherited
public @interface ToMySQLTableColumn {

    String value() default "";

}
