package com.github.rahulsom.javacat.common;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface GHGenerated {
  String from();
  String by();
}
