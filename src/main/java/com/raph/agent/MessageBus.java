package com.raph.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 轻量消息总线：负责 Agent inbox 投递、广播和 transcript 留痕。
 */
public class MessageBus {
    private final Map<String, Queue<AgentMessage>> inboxes = new ConcurrentHashMap<>();
    private final List<AgentMessage> transcript = new ArrayList<>();

    public void registerAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) return;
        inboxes.computeIfAbsent(agentId, ignored -> new ConcurrentLinkedQueue<>());
    }

    public synchronized void send(AgentMessage message) {
        if (message == null) return;
        transcript.add(message);

        if (AgentMessage.BROADCAST.equals(message.receiverId())) {
            // 普通进度广播只进入 transcript 并通知 coordinator，避免所有 Agent 被低价值进度反复唤醒。
            if (message.type() == AgentMessage.Type.REPORT_PROGRESS) {
                if (!"coordinator".equals(message.senderId())) {
                    inboxes.computeIfAbsent("coordinator", ignored -> new ConcurrentLinkedQueue<>()).offer(message);
                }
                return;
            }
            Set<String> ids = inboxes.keySet();
            for (String agentId : ids) {
                if (!agentId.equals(message.senderId())) {
                    inboxes.computeIfAbsent(agentId, ignored -> new ConcurrentLinkedQueue<>()).offer(message);
                }
            }
            return;
        }

        inboxes.computeIfAbsent(message.receiverId(), ignored -> new ConcurrentLinkedQueue<>()).offer(message);
    }

    public List<AgentMessage> drain(String agentId) {
        Queue<AgentMessage> queue = inboxes.computeIfAbsent(agentId, ignored -> new ConcurrentLinkedQueue<>());
        List<AgentMessage> messages = new ArrayList<>();
        AgentMessage message;
        while ((message = queue.poll()) != null) {
            messages.add(message);
        }
        messages.sort(Comparator.comparing(AgentMessage::timestamp));
        return messages;
    }

    public synchronized List<AgentMessage> transcript() {
        return List.copyOf(transcript);
    }

    public synchronized List<AgentMessage> recentTranscript(int limit) {
        if (limit <= 0 || transcript.isEmpty()) return List.of();
        int from = Math.max(0, transcript.size() - limit);
        return List.copyOf(transcript.subList(from, transcript.size()));
    }

    public synchronized void reset() {
        transcript.clear();
        inboxes.values().forEach(Queue::clear);
    }
}
