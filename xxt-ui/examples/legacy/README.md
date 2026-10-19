# 历史编辑器

WorkflowEditorPage.tsx 是 B 阶段前编辑器的逐字节归档，不进入当前 TypeScript / Vite 源集。
它依赖旧的完整定义读取、节点目录和更新接口，当前后端没有这些资源，不能作为可保存的产品入口。
当前创建入口是 src/pages/WorkflowCreatePage.tsx，提交完整 DSL 后只读取独立的摘要。
SQLConsole、DAGEditor、TaskMonitor 的历史源码仍保留在 src/pages，但已撤下产品路由。
