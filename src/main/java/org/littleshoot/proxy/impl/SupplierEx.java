package org.littleshoot.proxy.impl;

@FunctionalInterface
interface SupplierEx<T> {
  T get() throws Exception;
}
