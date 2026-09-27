import { BoardPage } from '@/features/board/board-page'
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { Compass } from 'lucide-react'
import { EmptyState } from '@/components/app/states'
import { Button } from '@/components/ui/button'
import { AppShell } from '@/components/layout/app-shell'
import { LoginPage } from '@/features/auth/login-page'
import { RegisterPage } from '@/features/auth/register-page'
import { ProtectedRoute } from '@/features/auth/protected-route'
import { useAuth } from '@/features/auth/auth-context'
import { AgentQueuePage } from '@/features/tickets/agent-queue-page'
import { TicketDetailPage } from '@/features/tickets/ticket-detail-page'
import { MyTicketsPage } from '@/features/tickets/my-tickets-page'
import { NewTicketPage } from '@/features/tickets/new-ticket-page'
import { IncidentBoardPage } from '@/features/incidents/incident-board-page'
import { IncidentDetailPage } from '@/features/incidents/incident-detail-page'
import { KnowledgeBasePage } from '@/features/knowledge/knowledge-base-page'
import { KnowledgeDocumentFormPage } from '@/features/knowledge/knowledge-document-form-page'
import { SettingsPage } from '@/features/settings/settings-page'
import { EvalDashboardPage } from '@/features/eval/eval-dashboard-page'

function NotFoundPage() {
  const navigate = useNavigate()
  return (
    <div className="mx-auto max-w-md px-5 py-16">
      <div className="glass rounded-xl">
        <EmptyState
          icon={Compass}
          title="Page not found"
          body="That address doesn't match anything in ResolveAI."
          action={
            <Button variant="secondary" size="sm" onClick={() => navigate('/')}>
              Back to my home page
            </Button>
          }
        />
      </div>
    </div>
  )
}

function RoleHome() {
  const { user } = useAuth()
  if (!user) return <Navigate to="/login" replace />
  return <Navigate to={user.role === 'CUSTOMER' ? '/tickets' : '/queue'} replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      <Route element={<ProtectedRoute><AppShell /></ProtectedRoute>}>
        <Route path="/" element={<RoleHome />} />

        <Route path="/tickets" element={<ProtectedRoute roles={['CUSTOMER']}><MyTicketsPage /></ProtectedRoute>} />
        <Route path="/tickets/new" element={<ProtectedRoute roles={['CUSTOMER']}><NewTicketPage /></ProtectedRoute>} />
        <Route path="/tickets/:id" element={<TicketDetailPage />} />

        <Route path="/queue" element={<ProtectedRoute roles={['AGENT', 'TEAM_LEAD', 'ADMIN']}><AgentQueuePage /></ProtectedRoute>} />
        <Route path="/board" element={<ProtectedRoute roles={['TEAM_LEAD', 'ADMIN']}><BoardPage /></ProtectedRoute>} />
        <Route path="/incidents" element={<ProtectedRoute roles={['AGENT', 'TEAM_LEAD', 'ADMIN']}><IncidentBoardPage /></ProtectedRoute>} />
        <Route path="/incidents/:id" element={<ProtectedRoute roles={['AGENT', 'TEAM_LEAD', 'ADMIN']}><IncidentDetailPage /></ProtectedRoute>} />
        <Route path="/knowledge" element={<ProtectedRoute roles={['AGENT', 'TEAM_LEAD', 'ADMIN']}><KnowledgeBasePage /></ProtectedRoute>} />
        <Route path="/knowledge/new" element={<ProtectedRoute roles={['ADMIN']}><KnowledgeDocumentFormPage /></ProtectedRoute>} />

        <Route path="/admin/settings" element={<ProtectedRoute roles={['ADMIN']}><SettingsPage /></ProtectedRoute>} />
        <Route path="/admin/evaluation" element={<ProtectedRoute roles={['ADMIN']}><EvalDashboardPage /></ProtectedRoute>} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>

    </Routes>
  )
}
