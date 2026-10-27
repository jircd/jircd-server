# Tests-first evidence

Commands, red/green results, suite counts and coverage are summarized in [build.md](build.md). Raw build logs were removed after review.

Service registration and runtime wiring from T009 were staged early so the no-op provider could produce behavioral socket failures. Integration tests already inherit server runtime dependencies, so their build file needed no change. Existing generic composition and lifecycle passed without production corrections.

Self-WHOIS and self-WHO intentionally reveal the caller's own real host. Cloak tests therefore use an independent requester as the display-host reference. Review-round edits add negotiated peer NAMES privacy checks and explicit NAMES after SAJOIN; these await coordinator validation.

The 351-byte channel rejection is WHO-driven even without identity decoration. Catalog slotBytes/worstCaseBytes assertions independently guard the identity reservation, including disabled installed providers. No production core behavior changed; the package documentation now refers to all bundled capabilities.
