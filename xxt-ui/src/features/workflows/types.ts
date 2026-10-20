export interface Position {
  x: number;
  y: number;
}

export interface WorkflowNode {
  id: string;
  type: string;          // 'source' | 'transform' | 'sink'
  nodeType: string;      // 'file.csv', 'filter', etc.
  label: string;
  position: Position;
  config: Record<string, unknown>;
  style?: Record<string, unknown>;
}

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string;
  targetHandle?: string;
  animated?: boolean;
  label?: string;
  style?: Record<string, unknown>;
}

export interface ScheduleConfig {
  enabled: boolean;
  scheduleType: 'fixed_rate' | 'cron' | 'immediate';
  interval?: string;
  cronExpression?: string;
}

export interface WorkflowMetadata {
  createdAt: string;
  updatedAt: string;
  executionHistory?: unknown[];
  schedule?: ScheduleConfig;
}

export interface Workflow {
  id: string;
  name: string;
  description: string;
  version: string;
  author: string;
  tags: string[];
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  metadata: WorkflowMetadata;
}

export interface NodeTypeDefinition {
  type: string;
  displayName: string;
  icon: string;
  category: 'source' | 'transform' | 'sink';
  description: string;
  config: Record<string, unknown>;
}

export interface ExecutionResult {
  workflowId: string;
  status: string;
  message: string;
  output: Record<string, unknown>;
}

// HTTP query DTOs are deliberately separate from the submitted Workflow DSL.
export interface WorkflowListItem { workflowId: string; revision: number; status: string }
export interface WorkflowPage { page: number; pageSize: number; items: WorkflowListItem[] }
export interface WorkflowSummary extends WorkflowListItem { recentExecutions: { executionId: string; status: string }[] }
export interface DefinitionCreated { workflowId: string; revision: number }
export interface ExecutionAccepted { workflowId: string; executionId: string }
export interface NodeExecutionDetail {
  nodeId: string; nodeType: string; status: string
  startTime?: number | null; endTime?: number | null; duration?: number | null
  recordsProcessed?: number | null; error?: string | null
}
export interface ExecutionDetail {
  executionId: string; workflowName: string; startTime: number; status: string
  endTime?: number | null; duration?: number | null; nodes: NodeExecutionDetail[]
}
export interface ExecutionHistoryData { workflowId: string; executions: ExecutionDetail[] }
