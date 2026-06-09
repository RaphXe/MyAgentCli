package com.raph.interaction;

/**
 * 后台中断监听句柄，调用 close() 停止监听。
 */
public interface InterruptWatcher extends AutoCloseable {
    InterruptWatcher NO_OP = () -> {};

    @Override
    void close();
}
