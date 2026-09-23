import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import type { CursorPage, TicketSummary } from '@/lib/types'

// The list endpoint caps a page at 100. A board is a whole-tenant view, so it follows the
// cursor - bounded, because an unbounded walk is how a dashboard becomes the slowest page
// in the product the day a tenant has a bad week.
const PAGE_SIZE = 100
const MAX_PAGES = 5

export function useBoardTickets() {
  return useQuery({
    queryKey: ['board-tickets'],
    queryFn: async () => {
      const all: TicketSummary[] = []
      let cursor: string | null = null
      let truncated = false
      for (let page = 0; page < MAX_PAGES; page++) {
        const params = new URLSearchParams({ size: String(PAGE_SIZE) })
        if (cursor) params.set('cursor', cursor)
        const res = (await api.get<CursorPage<TicketSummary>>(`/tickets?${params}`)).data
        all.push(...res.data)
        cursor = res.pagination.nextCursor
        if (!res.pagination.hasNext || !cursor) break
        if (page === MAX_PAGES - 1) truncated = true
      }
      return { tickets: all, truncated }
    },
    refetchInterval: 30_000,
  })
}
