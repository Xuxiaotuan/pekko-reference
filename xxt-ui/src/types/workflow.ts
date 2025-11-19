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
  config: Record<string, any>;
  style?: Record<string, any>;
}

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string;
  targetHandle?: string;
  animated?: boolean;
  label?: string;
  style?: Record<string, any>;
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
  executionHistory?: any[];
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
  config: Record<string, any>;
}

export interface ExecutionResult {
  workflowId: string;
  status: string;
  message: string;
  output: Record<string, any>;
}
