---
id: tools/local-file-tools
name: Local File Tool Usage
version: 1
description: Usage guidance for built-in local file tools.
tags: tools, filesystem
---
本地文件工具使用原则：
1. 读取项目前先用 project_tree、list_dir 或 search_files 缩小范围。
2. read_file 只读取能改变判断的关键文件。
3. write_file 写入前确认路径和已有上下文；写入后说明修改路径、目的和验证方式。
4. execute_command 适合编译、测试和 Git 状态检查；不要用它绕过已有的结构化工具。
