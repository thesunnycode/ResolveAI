import * as React from 'react'

/** True once `active` has stayed true for `afterMs` — a slow first request. */
export function useSlowFlag(active: boolean, afterMs = 3000) {
  const [slow, setSlow] = React.useState(false)
  React.useEffect(() => {
    if (!active) return
    const t = setTimeout(() => setSlow(true), afterMs)
    return () => {
      clearTimeout(t)
      setSlow(false)
    }
  }, [active, afterMs])
  return active && slow
}
