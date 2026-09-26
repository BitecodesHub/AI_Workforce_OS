import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'

/*
 * Every piece of platform data the interface reads, typed to match what the services return.
 *
 * Queries are keyed so that an action refreshes exactly what it changed: approving something
 * refreshes the approvals queue, the runs list and the badge in the navigation together, and a
 * screen never shows a count that disagrees with the list beneath it.
 */

export type Agent = {
  id: string
  key: string
  name: string
  category: string
  status: string
  revision: number | null
}

export type AgentGrant = {
  server: string
  tools: string[]
  scopes: string[]
  requireApproval: boolean
  maxCallsPerRun: number | null
}

export type AgentDetail = Agent & {
  systemPrompt: string | null
  goals: string | null
  maxSteps: number | null
  sealed: boolean
  grants: AgentGrant[]
}

export type Run = {
  id: string
  agentId: string
  taskId: string | null
  status: string
  trigger: string
  stepCount: number
  promptTokens: number
  completionTokens: number
  cost: number
  startedAt: string
  completedAt: string | null
  failureReason: string | null
}

export type RunStep = {
  id: string
  position: number
  kind: string
  detail: Record<string, unknown>
  provider: string | null
  model: string | null
  promptTokens: number
  completionTokens: number
  durationMs: number
  occurredAt: string
}

export type Approval = {
  id: string
  runId: string
  agentId: string
  tool: string | null
  actionClass: string
  summary: string
  /** The tool call's arguments, exactly as the model produced them, as a JSON string. */
  payload: string
  status: string
  requestedAt: string
  expiresAt: string
}

export type Task = {
  id: string
  agentId: string | null
  title: string
  status: string
  attempt: number
  maxAttempts: number
  result: string | null
  failureReason: string | null
  startedAt: string | null
  completedAt: string | null
  runId: string | null
}

export type Goal = {
  id: string
  title: string
  description: string
  status: string
  createdAt: string
  completedAt: string | null
  tasks: Task[]
}

export type Provider = {
  id: string
  displayName: string
  kind: string
  enabled: boolean
  credentialRef: string | null
  credentialStatus: string
  credentialCheckedAt: string | null
  circuitState: string
  regions: string[]
  modelCount: number
}

export type Model = {
  providerId: string
  modelId: string
  displayName: string
  contextWindow: number
  maxOutputTokens: number
  supportsTools: boolean
  supportsJsonMode: boolean
  supportsStreaming: boolean
  inputCostPerMillion: number
  outputCostPerMillion: number
  enabled: boolean
}

export type ModelPolicyCandidate = {
  position: number
  providerId: string
  modelId: string
  temperature: number | null
  maxOutputTokens: number | null
}

export type ModelPolicy = {
  /** False when nothing has been set and the router falls through to the workspace default, or the built-in sandbox. */
  configured: boolean
  exhaustedBehaviour: string
  maxAttemptsPerCandidate: number
  overallDeadlineSeconds: number
  compactOnOverflow: boolean
  candidates: ModelPolicyCandidate[]
}

export type ModelPolicyInput = {
  candidates: Array<{ providerId: string; modelId: string }>
}

export type Source = {
  id: string
  name: string
  kind: string
  status: string
  documentCount: number
  chunkCount: number
  embeddingProvider: string
  embeddingModel: string
  embeddingDimension: number
  lastIngestedAt: string | null
  lastError: string | null
}

export type SourceDocument = {
  id: string
  title: string
  mediaType: string
  status: string
  skipReason: string | null
  chunkCount: number
  indexedAt: string | null
  removedAtSource: boolean
}

export type Passage = {
  chunkId: string
  documentId: string
  documentTitle: string
  uri: string | null
  pageNumber: number | null
  heading: string | null
  content: string
  score: number
}

export type IngestResult = {
  documentId: string
  status: 'indexed' | 'skipped' | 'unchanged' | 'failed'
  chunkCount: number
  detail: string | null
  searchable: boolean
}

export type Tool = {
  name: string
  qualifiedName: string
  description: string
  sideEffect: 'READ' | 'WRITE' | 'OUTBOUND' | 'DESTRUCTIVE'
  requiredScopes: string[]
  alwaysRequiresApproval: boolean
}

export type Integration = {
  server: string
  displayName: string
  status: string
  sandbox: boolean
  reconnectRequired: boolean
  accountLabel: string | null
  grantedScopes: string[]
  missingScopes: string[]
  connectedAt: string | null
  tokenExpiresAt: string | null
  tools: Tool[]
}

export type Member = {
  userId: string
  displayName: string
  email: string
  role: string
  status: string
  joinedAt: string | null
  lastSignInAt: string | null
}

export type Role = {
  id: string
  name: string
  description: string
  system: boolean
  permissionVersion: number
  permissions: string[]
  holders: number
}

export type PermissionInfo = {
  code: string
  resource: string
  action: string
  description: string
  administrative: boolean
}

export type Invitation = {
  invitationId: string
  email: string
  roleName: string
  status: string
  expiresAt: string
  acceptedAt: string | null
  /** Present only on the response right after creation; never returned by the list. */
  token: string | null
  acceptUrl: string | null
}

/* ---- Reads ------------------------------------------------------------------------------------- */

export const useAgents = () => useQuery({ queryKey: ['agents'], queryFn: () => api<Agent[]>('/api/agents') })

export const useAgent = (id: string) =>
  useQuery({ queryKey: ['agents', id], queryFn: () => api<AgentDetail>(`/api/agents/${id}`) })

export const useRuns = () => useQuery({ queryKey: ['runs'], queryFn: () => api<Run[]>('/api/runs?size=50') })

export const useRun = (id: string) => useQuery({ queryKey: ['runs', id], queryFn: () => api<Run>(`/api/runs/${id}`) })

export const useRunSteps = (id: string) =>
  useQuery({ queryKey: ['runs', id, 'steps'], queryFn: () => api<RunStep[]>(`/api/runs/${id}/steps`) })

export const useApprovals = () =>
  useQuery({
    queryKey: ['approvals'],
    queryFn: () => api<Approval[]>('/api/approvals'),
    // The badge in the navigation depends on this, so it is kept reasonably fresh.
    refetchInterval: 30_000,
  })

export const useGoals = () => useQuery({ queryKey: ['goals'], queryFn: () => api<Goal[]>('/api/goals') })

export const useProviders = () =>
  useQuery({ queryKey: ['providers'], queryFn: () => api<Provider[]>('/api/providers') })

export const useModels = () =>
  useQuery({ queryKey: ['providers', 'models'], queryFn: () => api<Model[]>('/api/providers/models') })

export const useModelPolicy = () =>
  useQuery({ queryKey: ['model-policy'], queryFn: () => api<ModelPolicy>('/api/model-policy') })

export function useSetModelPolicy() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: ModelPolicyInput) =>
      api<ModelPolicy>('/api/model-policy', { method: 'PUT', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['model-policy'] }),
  })
}

export function useStoreCredential() {
  return useMutation({
    mutationFn: (input: { ref: string; kind: string; value: string }) =>
      api<{ ref: string; present: boolean }>(`/api/credentials/${input.ref}`, {
        method: 'PUT',
        body: { kind: input.kind, value: input.value },
      }),
  })
}

export const useSources = () => useQuery({ queryKey: ['sources'], queryFn: () => api<Source[]>('/api/sources') })

export const useSource = (id: string) =>
  useQuery({ queryKey: ['sources', id], queryFn: () => api<Source>(`/api/sources/${id}`) })

export const useSourceDocuments = (id: string) =>
  useQuery({
    queryKey: ['sources', id, 'documents'],
    queryFn: () => api<SourceDocument[]>(`/api/sources/${id}/documents`),
  })

export const useIntegrations = () =>
  useQuery({ queryKey: ['integrations'], queryFn: () => api<Integration[]>('/api/integrations') })

export const useMembers = () => useQuery({ queryKey: ['members'], queryFn: () => api<Member[]>('/api/users') })

export const useInvitations = (orgId: string) =>
  useQuery({
    queryKey: ['invitations', orgId],
    queryFn: () => api<Invitation[]>(`/api/orgs/${orgId}/invitations`),
    enabled: Boolean(orgId),
  })

export const useRoles = () => useQuery({ queryKey: ['roles'], queryFn: () => api<Role[]>('/api/roles') })

export const usePermissionCatalogue = () =>
  useQuery({ queryKey: ['roles', 'permissions'], queryFn: () => api<PermissionInfo[]>('/api/roles/permissions') })

/* ---- Writes ------------------------------------------------------------------------------------ */

export function useCreateGoal() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { title: string; agentId: string; instruction: string }) =>
      api<Goal>('/api/goals', {
        method: 'POST',
        body: {
          title: input.title,
          tasks: [{ agentId: input.agentId, title: input.title, instruction: input.instruction }],
        },
      }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['goals'] })
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['approvals'] })
    },
  })
}

export function useDecideApproval() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { id: string; approved: boolean; note?: string }) =>
      api<{ approvalId: string; status: string; runStatus: string }>(`/api/approvals/${input.id}/decision`, {
        method: 'POST',
        body: { approved: input.approved, note: input.note ?? null },
      }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['approvals'] })
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['goals'] })
    },
  })
}

export function useCreateAgent() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { key: string; name: string; category: string; systemPrompt: string }) =>
      api<Agent>('/api/agents', { method: 'POST', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['agents'] }),
  })
}

export function useUpdateAgent(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { systemPrompt: string; goals: string; maxSteps: number }) =>
      api<Agent>(`/api/agents/${id}/configuration`, { method: 'PUT', body: input }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['agents'] })
    },
  })
}

export function useCancelRun() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<Run>(`/api/runs/${id}/cancel`, { method: 'POST' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['approvals'] })
    },
  })
}

export function useCreateSource() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { name: string; kind: string }) => api<Source>('/api/sources', { method: 'POST', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['sources'] }),
  })
}

export function useUploadDocument(sourceId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return api<IngestResult>(`/api/sources/${sourceId}/documents`, { method: 'POST', form })
    },
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['sources'] })
    },
  })
}

export function useSearch() {
  return useMutation({
    mutationFn: (query: string) =>
      api<{ passages: Passage[]; grounded: boolean }>('/api/knowledge/search', {
        method: 'POST',
        body: { query, limit: 4 },
      }),
  })
}

export function useToggleProvider() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { id: string; enable: boolean }) =>
      api<Provider>(`/api/providers/${input.id}/${input.enable ? 'enable' : 'disable'}`, { method: 'POST' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['providers'] }),
  })
}

/** Agent names by id, for screens that show runs and approvals. */
export function useAgentNames(): Record<string, Agent> {
  const { data } = useAgents()
  return Object.fromEntries((data ?? []).map((agent) => [agent.id, agent]))
}

export function useCreateRun(agentId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { taskId?: string; instruction?: string }) =>
      api<Run>(`/api/agents/${agentId}/runs`, { method: 'POST', body: input }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['approvals'] })
      client.invalidateQueries({ queryKey: ['goals'] })
    },
  })
}

export function useCancelGoal(goalId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<Goal>(`/api/goals/${goalId}/cancel`, { method: 'POST' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['goals'] })
      client.invalidateQueries({ queryKey: ['runs'] })
      client.invalidateQueries({ queryKey: ['approvals'] })
    },
  })
}

export function useReindexSource(sourceId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<{ sourceId: string; status: string; documentsQueued: number }>(`/api/sources/${sourceId}/reindex`, { method: 'POST' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['sources'] })
      client.invalidateQueries({ queryKey: ['sources', sourceId] })
      client.invalidateQueries({ queryKey: ['sources', sourceId, 'documents'] })
    },
  })
}

export function useUpdateRole(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { name: string; description: string; permissions: string[] }) =>
      api<Role>(`/api/roles/${id}`, { method: 'PUT', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['roles'] }),
  })
}

export function useCreateRole() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { name: string; description: string; permissions: string[] }) =>
      api<Role>('/api/roles', { method: 'POST', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['roles'] }),
  })
}

export function useDeleteRole(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<void>(`/api/roles/${id}`, { method: 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['roles'] }),
  })
}

export function useDeleteRoleMutation() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/roles/${id}`, { method: 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['roles'] }),
  })
}

export function useUpdateRoleMutation() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { id: string; name: string; description: string; permissions: string[] }) =>
      api<Role>(`/api/roles/${input.id}`, { method: 'PUT', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['roles'] }),
  })
}

export function useInviteMember(orgId: string, email: string, roleName: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () =>
      api<Invitation>(`/api/orgs/${orgId}/invitations`, {
        method: 'POST',
        body: { email, roleName },
      }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['members'] })
      client.invalidateQueries({ queryKey: ['invitations', orgId] })
    },
  })
}

export function useUpdateMemberRole() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { userId: string; roleName: string }) =>
      api<Member>(`/api/users/${input.userId}/role`, { method: 'PUT', body: { roleName: input.roleName } }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['members'] }),
  })
}

export function useRemoveMember() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (userId: string) => api<void>(`/api/users/${userId}`, { method: 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['members'] }),
  })
}

export function useAcceptInvitation() {
  return useMutation({
    mutationFn: (input: { token: string; displayName: string; password: string }) =>
      api<{ userId: string; orgId: string; email: string; roleName: string }>('/api/invitations/accept', {
        method: 'POST',
        body: input,
      }),
  })
}

export function useChat() {
  throw new Error('useChat: /api/chat endpoint not implemented yet')
}

export type AuditEvent = {
  id: string
  sequence: number
  actorId: string
  actorKind: string
  onBehalfOf: string | null
  action: string
  resourceType: string
  resourceId: string | null
  outcome: 'succeeded' | 'failed' | 'denied'
  detail: Record<string, unknown>
  occurredAt: string
}

export type ActionCount = { action: string; count: number }
export type OutcomeCount = { outcome: string; count: number }

export type AnalyticsSummary = {
  windowStart: string
  windowEnd: string
  totalEvents: number
  byAction: ActionCount[]
  byOutcome: OutcomeCount[]
}

export function useAnalytics() {
  return useQuery({
    queryKey: ['analytics'],
    queryFn: () => api<AnalyticsSummary>('/api/analytics'),
  })
}

export function useAudit() {
  return useQuery({
    queryKey: ['audit'],
    queryFn: () => api<AuditEvent[]>('/api/audit?size=50'),
  })
}
