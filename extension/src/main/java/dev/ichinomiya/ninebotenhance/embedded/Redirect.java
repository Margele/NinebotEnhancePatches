package dev.ichinomiya.ninebotenhance.embedded;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Read by the patch, never at run time: a static method of {@link Redirects} that replaces calls of a platform method. For an
 * instance target the method takes the receiver first and the target's parameters after it; for a static target it takes the
 * target's parameters. The target has the annotated method's name and return type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Redirect {
    /** Dex type of the class the call sites name, such as {@code Landroid/media/MediaCodec;}. */
    String owner();
    boolean isStatic() default false;
    /** Dex type prefixes of the classes whose call sites are rewritten. */
    String[] scope() default {"Lcn/ninebot/"};
}
