// Mirrors the backend DTOs in docs/planning/05-API-CONTRACT.md and the
// com.resolveai.*.web.dto records. Kept as plain types, not generated,
// because the surface is stable and a generator is one more moving part
// this project doesn't need.

export type Role = 'CUSTOMER' | 'AGENT' | 'TEAM_LEAD' | 'ADMIN'

export interface AuthUser {
  id: number
  email: string
  fullName: string
  role: Role
  tenantId: number
  tenantSlug: string
  teamId: number | null
  teamName: string | null
  permissions: string[]
  agentProfile: {
    maxConcurrent: number
    openCount: number
    isAvailable: boolean
    shiftStart: string | null
    shiftEnd: string | null
  } | null
}

export interface CurrentUserResponse {
  id: number
  email: string
  fullName: string
  role: Role
  teamId: number | null
  teamName: string | null
  tenantId: number
  tenantSlug: string
  planTier: string
  permissions: string[]
  agentProfile: {
    maxConcurrent: number
    openCount: number
    isAvailable: boolean
    shiftStart: string | null
    shiftEnd: string | null
  } | null
}

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: { id: number; email: string; fullName: string; role: Role }
}

export type TicketStatus =
  | 'OPEN'
  | 'TRIAGED'
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'WAITING_ON_CUSTOMER'
  | 'PENDING_THIRD_PARTY'
  | 'RESOLVED'
  | 'CLOSED'

export type Priority = 'UNTRIAGED' | 'P1' | 'P2' | 'P3' | 'P4'

export interface UserRef {
  id: number
  fullName: string
}

export type SlaState = 'RUNNING' | 'PAUSED' | 'MET' | 'BREACHED' | 'CANCELLED'

/** The compact clock carried on a queue row — {@code SlaSummary.Clock} on the backend. */
export interface SlaChipClock {
  state: SlaState
  remainingBusinessMinutes: number | null
  atRisk: boolean
}

export interface SlaSummary {
  firstResponse: SlaChipClock | null
  resolution: SlaChipClock | null
}

export interface SlaSegment {
  state: 'RUNNING' | 'PAUSED'
  startedAt: string
  endedAt: string | null
  businessMinutes: number
  pauseReason: string | null
}

/** The full clock, with its segment history — only on the ticket detail's {@code sla.clocks}. */
export interface SlaClockDetail {
  kind: 'FIRST_RESPONSE' | 'RESOLUTION'
  state: SlaState
  policyVersion: string
  targetBusinessMinutes: number
  elapsedBusinessMinutes: number
  remainingBusinessMinutes: number
  metAt: string | null
  breachedAt: string | null
  nextDeadlineAt: string | null
  nextRung: number | null
  segments: SlaSegment[]
  escalationsFired: { rung: number; firedAt: string; elapsedMinutesAtFire: number }[]
  prediction: { predictedResolutionBusinessMinutes: number; basis: string; atRisk: boolean } | null
}

export interface SlaDetail {
  ticketId: number
  calendar: { timezone: string; workingDays: number[]; dayStart: string; dayEnd: string }
  clocks: SlaClockDetail[]
}

export interface TicketSummary {
  id: number
  reference: string
  subject: string
  status: TicketStatus
  priority: Priority
  category: string | null
  requester: UserRef | null
  assignee: UserRef | null
  team: { id: number; name: string } | null
  incidentRef: string | null
  sla: SlaSummary | null
  messageCount: number
  createdAt: string
  updatedAt: string
  /** Newest customer-visible message; null until someone has replied. */
  lastPublicReply?: { at: string; fromSupport: boolean } | null
}

export interface CursorPage<T> {
  data: T[]
  pagination: { size: number; nextCursor: string | null; hasNext: boolean }
}

export interface MessageView {
  id: number
  authorId: number
  authorName: string
  authorRole: Role
  body: string
  visibility: 'PUBLIC' | 'INTERNAL'
  isFirstResponse: boolean
  fromDraftId: number | null
  createdAt: string
}

/** {@code GET /tickets/{id}} for AGENT+ — see TicketDetailResponse. Distinct shape from the
 * customer's own view ({@code TicketCustomerResponse}), deliberately: see that class's doc. */
export interface TicketDetail {
  id: number
  reference: string
  subject: string
  body: string
  status: TicketStatus
  priority: Priority
  category: string | null
  requester: UserRef | null
  assignee: UserRef | null
  team: { id: number; name: string } | null
  reopenCount: number
  messages: MessageView[]
  sla: SlaDetail | null
  incident: { id: number; reference: string } | null
  analysisStatus: string
  latestDraftId: number | null
  /** Customer view only: the first-response promise, once triage has set it. */
  responseTarget?: ResponseTarget | null
  etag: string
  createdAt: string
  updatedAt: string
}

export interface ResponseTarget {
  state: 'RUNNING' | 'PAUSED' | 'MET' | 'BREACHED'
  targetBusinessMinutes: number
  timezone: string
  workingDays: number[]
  dayStart: string
  dayEnd: string
}

export interface TriageSignalsView {
  category: string
  reportedImpact: string
  serviceDownClaimed: boolean
  dataLossClaimed: boolean
  paymentAffected: boolean
  linguisticUrgency: string
  confidence: number
}

export type AnalysisStatus = 'PROCESSING' | 'READY' | 'UNAVAILABLE'

export interface AnalysisView {
  status: AnalysisStatus
  signals: TriageSignalsView | null
  modelId: string | null
  promptVersion: string | null
  manualTriageRequired: boolean
  reason: string | null
}

export type ClaimVerdict = 'PENDING' | 'SUPPORTED' | 'PARTIAL' | 'NOT_SUPPORTED' | 'FAILED_NUMERIC_CHECK'

export interface CitationView {
  chunkId: number
  documentId: number
  documentTitle: string
  source: 'RUNBOOK' | 'ARTICLE' | 'RESOLVED_TICKET'
  charStart: number
  charEnd: number
  snippet: string
}

export interface ClaimView {
  ordinal: number
  text: string
  verdict: ClaimVerdict
  kept: boolean
  rejectionReason: string | null
  citations: CitationView[]
}

/** Matches the backend's {@code DraftStatus} enum exactly — see ck_draft_status. */
export type DraftStatus =
  | 'PENDING'
  | 'SHOWN'
  | 'SUPPRESSED_LOW_COVERAGE'
  | 'SUPPRESSED_NO_EVIDENCE'
  | 'FAILED'

export interface DraftView {
  id: number
  status: DraftStatus
  coverage: number | null
  suppressionReason: string | null
  assembledText: string | null
  claims: ClaimView[]
  unresolvedAspects: string[]
}

export type IncidentStatus = 'PROPOSED' | 'CONFIRMED' | 'REJECTED' | 'MITIGATED' | 'RESOLVED'

export interface DetectionView {
  method: 'CLUSTER' | 'MANUAL'
  clusterSizeAtDetection: number
  arrivalRateMultiple: number
  baselineNote: string
  gateThresholds: { minClusterSize: number; minRateMultiple: number; windowMinutes: number }
  firstTicketAt: string
  detectedAt: string
  timeToDetectSeconds: number
}

export interface IncidentSummary {
  id: number
  reference: string
  title: string
  status: IncidentStatus
  linkedTicketCount: number
  timeToDetectSeconds: number
  detection: DetectionView
}

export interface LinkedTicketView {
  ticketId: number
  reference: string
  subject: string
  linkConfidence: number | null
  linkedAt: string
  linkedBy: UserRef | null
  detachedAt: string | null
}

export interface IncidentUpdateView {
  id: number
  body: string
  visibility: 'PUBLIC' | 'INTERNAL'
  authorName: string
  publishedAt: string
  delivery: { total: number; sent: number; pending: number; failed: number }
}

export interface IncidentDetail extends IncidentSummary {
  summary: string | null
  titleGeneratedBy: string | null
  confirmedBy: UserRef | null
  confirmedAt: string | null
  rejectedReason: string | null
  linkedTickets: LinkedTicketView[]
  detachedTickets: LinkedTicketView[]
  updates: IncidentUpdateView[]
  etag: string
}

export interface KnowledgeDocumentSummary {
  id: number
  title: string
  source: 'RUNBOOK' | 'ARTICLE' | 'RESOLVED_TICKET'
  chunkCount: number
  indexed: boolean
  uri: string | null
  createdAt: string
}

export interface RuleTrace {
  rule: string
  matched: boolean
  effect: string
  note: string
}

export interface PriorityRationaleView {
  ticketId: number
  computedPriority: string
  policyVersion: string
  decidedAt: string | null
  inputs: { fromModel: Record<string, unknown>; fromSystem: Record<string, unknown> } | null
  rules: RuleTrace[]
  humanReadable: string
  overridable: boolean
}

export interface AdminSlaPolicyRow {
  priority: 'P1' | 'P2' | 'P3' | 'P4'
  configured: boolean
  firstResponseMinutes: number | null
  resolutionMinutes: number | null
  escalationRungs: number[]
  versionLabel: string | null
  effectiveFrom: string | null
}

export interface AdminCalendar {
  timezone: string
  workingDays: number[]
  dayStart: string
  dayEnd: string
}

export interface AdminAiPolicy {
  tenantId: number
  externalModelAllowed: boolean
  allowedProviders: string[]
  piiRedactionRequired: boolean
  monthlyBudgetMicros: number
  currentMonthSpendMicros: number
  budgetRemainingMicros: number
  retentionDays: number
}

export interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  instance: string
  errorCode: string
  traceId: string
  timestamp: string
  errors?: { field: string; code: string; message: string; rejectedValue?: string }[]
}
