# 实验材料索引

主分支保留实验 README、结构化结果、源码清单、归档索引、SHA-256 清单和失败结论。

原始压缩包、分卷归档和超过 1 MiB 的运行日志不进入 Git 主分支。它们保存在本地备份 bundle `feature-pekkosync-mvp-cleanup-datafusion-full-20261119.bundle` 中，恢复时执行：

```sh
git clone /path/to/feature-pekkosync-mvp-cleanup-datafusion-full-20261119.bundle pekko-reference-full-evidence
```

各实验目录中的原始归档链接统一指向本说明。结构化摘要和校验清单继续作为网站、代码审查和文章引用的依据。

本轮瘦身删除了当前分支工作树中的压缩归档、分卷文件和超过 1 MiB 的运行日志。原始 Git 历史已在本地 bundle 中封存。

当前独立 Graph 验证材料

- [单表 Graph](2026-11-19-table-graph/README.md)
- [多表共享 Reader](2026-11-20-table-graph-multi/README.md)
- [Journal 重启恢复](2026-11-20-table-graph-restart/README.md)
- [运行中取消](2026-11-20-table-graph-cancel/README.md)
