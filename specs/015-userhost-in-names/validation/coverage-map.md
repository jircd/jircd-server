# Coverage and SPI verification

FR-001/002: provider unit and UserhostInNamesTest exact CAP and NAMES; FR-003: cloak enabled/disabled comparison against JOIN display identity and real-host exclusion; FR-004: bare/targeted/JOIN/SAJOIN, visibility, WHO, 366. FR-005/006: four-way status matrix and NamesByteBudgetTest complete UTF-8 tokens/line packing. FR-007: actual discovery catalog bounds/startup/reload, 366 independently. FR-008/009: UserhostInNamesLifecycleTest configuration, toggle/reload, notification classes, atomic REQ. SC-001/002: these tests and socket walkthrough. SC-003: coordinator irctest. SC-004: compiled tagged load plus coordinator measurements.

Verified existing onNamesIdentity registers IDENTITY with ReplyBound, MemberSnapshot exposes only permitted displayHost, MemberReplies composes status/identity independently, and ReplyLimits exposes identMax/hostMax. No SPI mismatch or core production change is needed.
