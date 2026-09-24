import { BoardPage } from '@/features/board/board-page'
import { Route, Routes, useNavigate } from 'react-router-dom'
import { Compass } from 'lucide-react'
import { EmptyState } from '@/components/app/states'
import { Button } from '@/components/ui/button'
import { AppShell } from '@/components/layout/app-shell'
import { LandingPage } from '@/components/landing/landing-page'
import { LoginPage } from '@/features/auth/login-page'
import { RegisterPage } from '@/features/auth/register-page'
import { RegisterBusinessPage } from '@/features/auth/register-business-page'
import { InviteAcceptPage } from '@/features/auth/invite-accept-page'
import { ForgotPasswordPage } from '@/features/auth/forgot-password-page'
import { ResetPasswordPage } from '@/features/auth/reset-password-page'
import { ProtectedRoute } from '@/features/auth/protected-route'
import { homeFor, useAuth } from '@/features/auth/auth-context'
import { AgentQueuePage } from '@/features/tickets/agent-queue-page'
import { TicketDetailPage } from '@/features/tickets/ticket-detail-page'
import { MyTicketsPage } from '@/features/tickets/my-tickets-page'
import { NewTicketPage } from '@/features/tickets/new-ticket-page'
import { IncidentBoardPage } from '@/features/incidents/incident-board-page'
import { IncidentDetailPage } from '@/features/incidents/incident-detail-page'
import { KnowledgeBasePage } from '@/features/knowledge/knowledge-base-page'
import { KnowledgeDocumentFormPage } from '@/features/knowledge/knowledge-document-form-page'
import { BulkImportPage } from '@/features/knowledge/bulk-import-page'
import { SettingsPage } from '@/features/settings/settings-page'
import { EvalDashboardPage } from '@/features/eval/eval-dashboard-page'

function NotFoundPage() {
  const navigate = useNavigate()
  const { user } = useAuth()
  return (
    <div className="mx-auto max-w-md px-5 py-16">
      <div className="glass rounded-xl">
        <EmptyState
          icon={Compass}
          title="Page not found"
          body="That address doesn't match anything in ResolveAI."
          action={
            <Button variant="secondary" size="sm" onClick={() => navigate(user ? homeFor(user.role) : '/')}>
              Back to my home page
            </Button>
          }
        />
      </div>
    </div>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/register-business" element={<RegisterBusinessPage />} />
      <Route path="/invite/accept" element={<InviteAcceptPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />

      <Route element={<ProtectedRoute><AppShell /></ProtectedRoute>}>
        <Route path="/tickets" element={<ProtectedRoute roles={['CUSTOMER']}><MyTicketsPage /></ProtectedRoute>} />
        <Route path="/tickets/new" element={<ProtectedRoute roles={['CUSTOMER']}><NewTicketPage /></ProtectedRoute>} />
        <Route path="/tickets/:id" element={<TicketDetailPage />} />

        <Route path="/queue" element={<ProtectedRoute roles={['AGENT', 'TEAM_LEAD', 'ADMIN']}><AgentQueuePage /></ProtectedRoute>} />
        <Route path="/board" element={<ProtectedRoute roles={['TEAM_LEAD', 'ADMIN']}><BoardPage /></ProtectedRoute>} />
        <Route path="/incidents" element={<ProtectedRoute roles={['AGENT', 'TEAM_LEAD', 'ADMIN']}><IncidentBoardPage /></ProtectedRoute>} />
        <Route path="/incidents/:id" element={<ProtectedRoute roles={['AGENT', 'TEAM_LEAD', 'ADMIN']}><IncidentDetailPage /></ProtectedRoute>} />
        <Route path="/knowledge" element={<ProtectedRoute roles={['AGENT', 'TEAM_LEAD', 'ADMIN']}><KnowledgeBasePage /></ProtectedRoute>} />
        <Route path="/knowledge/new" element={<ProtectedRoute roles={['ADMIN']}><KnowledgeDocumentFormPage /></ProtectedRoute>} />
        <Route path="/knowledge/bulk-import" element={<ProtectedRoute roles={['ADMIN']}><BulkImportPage /></ProtectedRoute>} />

        <Route path="/settings" element={<ProtectedRoute roles={['ADMIN']}><SettingsPage /></ProtectedRoute>} />
        <Route path="/evaluation" element={<ProtectedRoute roles={['ADMIN']}><EvalDashboardPage /></ProtectedRoute>} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>

    </Routes>
  )
}
