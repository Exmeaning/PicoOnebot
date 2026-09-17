package pico.onebot.core

import java.util.concurrent.atomic.AtomicLong

/**
 * 全局收发计数(BotStatus 的 `msgSent` / `msgRecv` 来源)。
 * 只统计"成真的 OneBot 消息":收到并转成事件的入站消息、发送成功的出站消息。
 * 进程内计数,重启归零 —— 面板上是"本次运行以来",不做持久化。
 */
object Counters {

    private val sent = AtomicLong()
    private val recv = AtomicLong()

    fun onSent() { sent.incrementAndGet() }
    fun onRecv() { recv.incrementAndGet() }

    fun sent(): Long = sent.get()
    fun recv(): Long = recv.get()
}
