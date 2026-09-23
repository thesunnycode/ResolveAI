/**
 * The one recurring visual signature across the app: a clock ring (the SLA
 * countdown this whole product is built around) resolving into a checkmark.
 * Used at nav-bar scale and, larger, on the auth screens - deliberately the
 * same mark both places rather than a wordmark-only nav and a different
 * graphic on auth, which is how a product ends up with no visual identity
 * a user could actually recognize.
 */
export function Logomark({ size = 28, className }: { size?: number; className?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
      aria-hidden
    >
      <rect width="32" height="32" rx="9" fill="currentColor" className="text-primary" />
      <circle cx="16" cy="16" r="8.25" stroke="white" strokeWidth="1.6" strokeOpacity="0.9" fill="none" />
      <path
        d="M12.1 16.4l2.7 2.6 5.1-5.6"
        stroke="white"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
