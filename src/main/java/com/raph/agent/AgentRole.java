package com.raph.agent;

public enum AgentRole {
    Coordinator("维持目标、拆任务、判断是否收尾"),
    Researcher("负责信息检索、数据分析、方案生成"),
    Coder("修改代码、调用工具"),
    Reviewer("审查方案、代码、结果"),
    Tester("验证方案、代码、结果");
    private final String description;

    AgentRole(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
